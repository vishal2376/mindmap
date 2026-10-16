package com.mindmap.plugin.analysis

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.SuperMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * [CallableProvider] for Kotlin.
 *
 * # Responsibilities
 * - Finds the nearest enclosing [KtNamedFunction] at the cursor.
 * - Resolves outbound call expressions using the Kotlin Analysis API (`analyze {}`).
 * - Finds callers via `ReferencesSearch`, widened through light methods so Java callers
 *   of Kotlin functions are surfaced (Kotlin ↔ Java cross-language seam).
 * - Detects JUnit-style test functions via annotation short-name.
 *
 * # Cross-language seam
 *
 * `KtNamedFunction.toLightMethods()` produces a list of `PsiMethod` facades that the
 * Java world sees — this is how `ReferencesSearch` finds Java call sites of a Kotlin
 * function. Symmetrically, when a Kotlin body calls a Java method, the Analysis API
 * resolves the call's `symbol.psi` to a `PsiMethod`; we wrap it as
 * [CallableFunction.Java] and return it so the orchestrator can keep walking through
 * [JavaCallableProvider] without either provider needing to know the other exists.
 *
 * # Contract reminders
 * Every method returns **one hop** only. No recursion, no caps — [GraphAnalyzer] owns
 * depth bookkeeping and the [com.mindmap.plugin.analysis.GraphAnalyzer] size limits.
 */
class KotlinCallableProvider : CallableProvider {

    companion object {
        private val LOG = Logger.getInstance(KotlinCallableProvider::class.java)

        // Per-call cap so widely-used functions can't blow up the outbound phase;
        // GraphAnalyzer still applies the global MAX_NODES on top of this.
        private const val MAX_CALLS_PER_FUNCTION = 50

        // `analyze {}` is an inline function: Kotlin splices its *compiled* body
        // directly into our bytecode, baking in whatever internal KaSessionProvider
        // overloads existed in the SDK used to compile. Those internal overloads are
        // not ABI-stable across IDE releases (binary-incompatible on older IDEs with
        // NoSuchMethodError on KaSessionProvider's lifecycle methods). The public entry
        // point `AnalyzeKt.analyze(KtElement, Function1)` itself IS stable, so we call
        // it reflectively instead -- that avoids inlining, so the call resolves against
        // whatever Kotlin plugin is actually loaded in the running IDE rather than the
        // one we compiled against.
        private val analyzeMethod: Method? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.analysis.api.AnalyzeKt")
                    .getMethod("analyze", KtElement::class.java, Function1::class.java)
            } catch (e: ReflectiveOperationException) {
                LOG.warn("Kotlin Analysis API analyze() entry point not found", e)
                null
            }
        }
    }

    private fun <R> analyzeCompat(element: KtElement, action: (KaSession) -> R): R? {
        val method = analyzeMethod ?: return null
        @Suppress("UNCHECKED_CAST")
        return try {
            method.invoke(null, element, action) as R
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    override val languageId: String = "kotlin"

    override fun supports(callable: CallableFunction): Boolean = callable is CallableFunction.Kotlin

    /**
     * Walks up from [element] to the nearest `KtNamedFunction`. Returns `null` if the
     * cursor is outside any Kotlin function — the next provider (Java) will try next.
     */
    override fun callableAt(element: PsiElement): CallableFunction? {
        val fn = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java) ?: return null
        return CallableFunction.Kotlin(fn)
    }

    /**
     * Resolves every [KtCallExpression] in the body using `analyze {}` (invoked via
     * [analyzeCompat], see its doc for why).
     *
     * `resolveToCall()` is a `KaSession` extension — the call must happen **inside** the
     * `with(session) { … }` block, otherwise the Kotlin compiler rejects it. Downstream
     * work (wrapping into a [CallableFunction]) can happen after the block since it only
     * touches plain PSI.
     *
     * If the resolved target is a `PsiMethod` (Kotlin calling Java), we wrap it as
     * [CallableFunction.Java] — that is how cross-language outbound edges appear.
     */
    override fun outboundTargets(
        project: Project,
        callable: CallableFunction
    ): List<CallableFunction> {
        val fn = (callable as? CallableFunction.Kotlin)?.fn ?: return emptyList()
        val body = fn.bodyExpression ?: return emptyList()
        val calls = PsiTreeUtil.findChildrenOfType(body, KtCallExpression::class.java)
            .let { if (it.size > MAX_CALLS_PER_FUNCTION) it.take(MAX_CALLS_PER_FUNCTION) else it.toList() }

        val results = mutableListOf<CallableFunction>()
        try {
            analyzeCompat(fn) { session ->
                with(session) {
                    for (callExpr in calls) {
                        try {
                            val resolved =
                                callExpr.resolveToCall()?.singleFunctionCallOrNull() ?: continue
                            val targetPsi = resolved.symbol.psi ?: continue
                            val wrapped = wrapResolvedTarget(targetPsi) ?: continue
                            results.add(wrapped)
                        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                            throw ce
                        } catch (e: Exception) {
                            LOG.debug("Skipped unresolved outbound call", e)
                        }
                    }
                }
            }
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error in Kotlin outbound analysis", e)
            com.mindmap.plugin.DebugLog.error(
                "Error in Kotlin outbound analysis for '${fn.name}'",
                e
            )
        }
        return results
    }

    /**
     * Cross-language wrap: the Analysis API may return either a Kotlin function PSI
     * (project code or Kotlin stdlib) or a Java `PsiMethod` facade (a call into a Java
     * library or Java source). We map each to the corresponding [CallableFunction] case
     * so the Java route is picked up by [JavaCallableProvider] on follow-up.
     */
    private fun wrapResolvedTarget(psi: PsiElement): CallableFunction? {
        return when (psi) {
            is KtNamedFunction -> if (!psi.name.isNullOrEmpty()) CallableFunction.Kotlin(psi) else null
            is PsiMethod -> {
                // `unwrapped` surfaces the real Kotlin source when the PSI is a light class;
                // prefer that so navigation lands on the .kt file, not the light .class stub.
                val source = psi.unwrapped
                if (source is KtNamedFunction) CallableFunction.Kotlin(source)
                else CallableFunction.Java(psi)
            }

            else -> null
        }
    }

    /**
     * Inbound search via light methods.
     *
     * `toLightMethods()` is the Kotlin↔Java bridge: searching references to the light
     * method finds Kotlin *and* Java call sites. We also include super declarations
     * so callers of an abstract base surface on the concrete override.
     *
     * Each reference's enclosing function may be a `KtNamedFunction` (Kotlin caller) or
     * a `PsiMethod` (Java caller). We wrap accordingly.
     */
    override fun inboundCallers(
        project: Project,
        callable: CallableFunction,
        scope: GlobalSearchScope
    ): List<CallableFunction> {
        val fn = (callable as? CallableFunction.Kotlin)?.fn ?: return emptyList()
        return try {
            val lightMethods = fn.toLightMethods()
            val superMethods = lightMethods.flatMap { lm ->
                SuperMethodsSearch.search(lm, null, true, false).findAll().map { it.method }
            }
            val searchTargets: List<PsiElement> =
                (listOf<PsiElement>(fn) + lightMethods + superMethods).distinct()

            searchTargets
                .flatMap { target -> ReferencesSearch.search(target, scope).findAll() }
                .distinctBy { it.element }
                .mapNotNull { ref ->
                    // First match wins — PsiTreeUtil walks up, so the closest wrapping callable
                    // in either language is picked.
                    val kotlinParent =
                        PsiTreeUtil.getParentOfType(ref.element, KtNamedFunction::class.java)
                    if (kotlinParent != null) return@mapNotNull CallableFunction.Kotlin(kotlinParent)
                    val javaParent = PsiTreeUtil.getParentOfType(ref.element, PsiMethod::class.java)
                    if (javaParent != null) CallableFunction.Java(javaParent) else null
                }
                .distinct()
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error searching Kotlin inbound references", e)
            com.mindmap.plugin.DebugLog.error(
                "Error searching Kotlin inbound references for '${fn.name}'",
                e
            )
            emptyList()
        }
    }

    /**
     * For an abstract/interface Kotlin function, returns concrete overrides from
     * project sources (Kotlin or Java). Bounded to 3 to prevent graph explosion on
     * interfaces with many implementers.
     */
    override fun implementations(
        project: Project,
        callable: CallableFunction
    ): List<CallableFunction> {
        val fn = (callable as? CallableFunction.Kotlin)?.fn ?: return emptyList()
        if (fn.bodyExpression != null) return emptyList()
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            fn.toLightMethods().flatMap { lm ->
                OverridingMethodsSearch.search(lm, scope, true).toList()
                    .mapNotNull { overridingMethod ->
                        // `navigationElement` lands on the real source — Kotlin for .kt overrides,
                        // Java for .java overrides — so we wrap into the matching case.
                        when (val nav = overridingMethod.navigationElement) {
                            is KtNamedFunction -> if (nav.bodyExpression != null) CallableFunction.Kotlin(
                                nav
                            ) else null

                            is PsiMethod -> if (nav.body != null) CallableFunction.Java(nav) else null
                            else -> null
                        }
                    }
            }.take(3)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Matches the common JUnit shortcuts by annotation short-name — covers `@Test`
     * from JUnit 4/5 and `@ParameterizedTest` / `@RepeatedTest` from Jupiter. Uses
     * short-name only so we don't need to resolve the annotation class.
     */
    override fun isTestFunction(callable: CallableFunction): Boolean {
        val fn = (callable as? CallableFunction.Kotlin)?.fn ?: return false
        return fn.annotationEntries.any {
            val name = it.shortName?.asString() ?: return@any false
            name == "Test" || name == "ParameterizedTest" || name == "RepeatedTest"
        }
    }
}
