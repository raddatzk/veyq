package me.raddatz.veyq.buildlogic

import org.openapitools.codegen.languages.JavaJAXRSSpecServerCodegen
import org.openapitools.codegen.model.ModelMap
import org.openapitools.codegen.model.OperationsMap

/**
 * `jaxrs-spec` with every generated interface keeping its class-level `@Path` — the tag's common path prefix, as the
 * generator wrote it up to 7.23.0. [OpenApiGenerate] runs this class (by its name) instead of `jaxrs-spec`.
 *
 * Why: 7.24.0 (openapi-generator #23871, "Fix path shadowing", issue #23414) empties a tag's class-level path whenever
 * another tag OF THE SAME SPEC owns a path under it, and moves the full path onto each method. That breaks Quarkus REST
 * here instead of fixing it: the check sees one spec at a time (we generate spec by spec), so `BlobApi` became
 * `@Path("")` while `ScopesApi` (another spec) kept `@Path("/api/scopes")`, and Quarkus REST routes
 * `POST /api/scopes/{scopeId}/blob-uploads-batch` to the longer class prefix and answers 404 — measured on 7.27.0:
 * `OperatorBlindProbeTest` 107 of 108 red with `Expected status code <200> but was <404>` from
 * `ScopeAccessWorld.startUpload`. Quarkus REST already resolves the overlap the check guards against (a class path
 * that is a prefix of another class's: `/api/scopes/{scopeId}` beside `/api/scopes/{scopeId}/nodes/{nodeId}`), which
 * every slice test exercises; so the pre-7.24 shape is the one that routes. The generator has no option for it.
 *
 * Only an interface whose prefix the generator dropped is touched: the prefix is recomputed segment by segment, exactly
 * as 7.23.0's `AbstractJavaJAXRSServerCodegen.getCommonPath` did, and stripped from each method's path. A tag whose
 * common prefix is `/` keeps the empty class path, as before.
 */
class ClassPathKept : JavaJAXRSSpecServerCodegen() {
    override fun postProcessOperationsWithModels(objs: OperationsMap, allModels: List<ModelMap>): OperationsMap {
        val processed = super.postProcessOperationsWithModels(objs, allModels)
        val ops = processed.operations?.operation.orEmpty()
        if (ops.isEmpty() || processed["commonPath"] != "") return processed
        val common = ops.map { it.path }.reduce(::commonPrefix)
        if (common.isEmpty()) return processed
        ops.forEach { op ->
            op.path = op.path.removePrefix(common)
            op.subresourceOperation = op.path.length > 1
        }
        processed["commonPath"] = common
        return processed
    }

    private fun commonPrefix(a: String, b: String): String =
        a.split('/').filter { it.isNotEmpty() }.zip(b.split('/').filter { it.isNotEmpty() })
            .takeWhile { (x, y) -> x == y }
            .joinToString("") { "/${it.first}" }
}
