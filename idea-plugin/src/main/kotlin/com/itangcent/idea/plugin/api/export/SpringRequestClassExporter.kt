package com.itangcent.idea.plugin.api.export

import com.google.inject.Inject
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.itangcent.common.constant.HttpMethod
import com.itangcent.common.model.Header
import com.itangcent.common.model.Request
import com.itangcent.common.utils.KV
import com.itangcent.common.utils.any
import com.itangcent.common.utils.isNullOrEmpty
import com.itangcent.common.utils.tinyString
import com.itangcent.idea.plugin.rule.computer
import com.itangcent.idea.plugin.utils.SpringClassName
import com.itangcent.intellij.jvm.AnnotationHelper
import com.itangcent.intellij.jvm.element.ExplicitMethod
import com.itangcent.intellij.jvm.element.ExplicitParameter
import com.itangcent.intellij.util.hasFile
import org.apache.commons.lang3.StringUtils

open class SpringRequestClassExporter : AbstractRequestClassExporter() {

    @Inject
    private val annotationHelper: AnnotationHelper? = null

    @Inject
    private val commentResolver: CommentResolver? = null

    override fun processClass(cls: PsiClass, kv: KV<String, Any?>) {

        val ctrlRequestMappingAnn = findRequestMapping(cls)
        var basePath: String = findHttpPath(ctrlRequestMappingAnn) ?: ""
        val prefixPath = ruleComputer!!.computer(ClassExportRuleKeys.CLASS_PREFIX_PATH, cls)
        if (!prefixPath.isNullOrBlank()) {
            basePath = contractPath(prefixPath, basePath) ?: ""
        }

        val ctrlHttpMethod = findHttpMethod(ctrlRequestMappingAnn)

        kv["basePath"] = basePath
        kv["ctrlHttpMethod"] = ctrlHttpMethod
    }

    override fun hasApi(psiClass: PsiClass): Boolean {
        return SpringClassName.SPRING_CONTROLLER_ANNOTATION.any {
            annotationHelper!!.hasAnn(psiClass, it)
        }
    }

    override fun isApi(psiMethod: PsiMethod): Boolean {
        return SpringClassName.SPRING_REQUEST_MAPPING_ANNOTATIONS.any {
            annotationHelper!!.hasAnn(psiMethod, it)
        }
    }

    override fun processMethodParameter(request: Request, param: ExplicitParameter, typeObject: Any?, paramDesc: String?) {

        if (isRequestBody(param.psi())) {
            requestHelper!!.setMethodIfMissed(request, HttpMethod.POST)
            requestHelper.addHeader(request, "Content-Type", "application/json")
            requestHelper.setJsonBody(
                    request,
                    typeObject,
                    paramDesc
            )
            return
        }

        if (isModelAttr(param.psi())) {
            if (request.method == HttpMethod.GET) {
                addParamAsQuery(param, typeObject, request)
            } else {
                if (request.method == HttpMethod.NO_METHOD) {
                    requestHelper!!.setMethod(request,
                            ruleComputer!!.computer(ClassExportRuleKeys.METHOD_DEFAULT_HTTP_METHOD, param.containMethod())
                                    ?: HttpMethod.POST)
                }
                addParamAsForm(param, request, typeObject, paramDesc)
            }
            return
        }

        var ultimateComment = (paramDesc ?: "")
        param.getType()?.let { duckType ->
            commentResolver!!.resolveCommentForType(duckType, param.psi())?.let {
                ultimateComment = "$ultimateComment $it"
            }
        }
        val requestHeaderAnn = findRequestHeader(param.psi())
        if (requestHeaderAnn != null) {

            var headName = requestHeaderAnn.any("value", "name")
            if (headName.isNullOrEmpty()) {
                headName = param.name()
            }

            var required = findParamRequired(requestHeaderAnn)
            if (!required && ruleComputer!!.computer(ClassExportRuleKeys.PARAM_REQUIRED, param) == true) {
                required = true
            }

            var defaultValue = requestHeaderAnn["defaultValue"]

            if (defaultValue == null
                    || defaultValue == SpringClassName.ESCAPE_REQUEST_HEADER_DEFAULT_NONE
                    || defaultValue == SpringClassName.REQUEST_HEADER_DEFAULT_NONE) {
                defaultValue = ""
            }

            val header = Header()
            header.name = headName?.toString()
            header.value = defaultValue.toString()
            header.example = defaultValue.toString()
            header.desc = ultimateComment
            header.required = required
            requestHelper!!.addHeader(request, header)
            return
        }

        val pathVariableAnn = findPathVariable(param.psi())
        if (pathVariableAnn != null) {

            var pathName = pathVariableAnn["value"]?.toString()

            if (pathName == null) {
                pathName = param.name()
            }

            requestHelper!!.addPathParam(request, pathName, ultimateComment)
            return
        }

        var paramName: String? = null
        var required = false
        var defaultVal: Any? = null

        val requestParamAnn = findRequestParam(param.psi())

        if (requestParamAnn != null) {
            paramName = findParamName(requestParamAnn)
            required = findParamRequired(requestParamAnn)

            defaultVal = requestParamAnn["defaultValue"]

            if (defaultVal == null
                    || defaultVal == SpringClassName.ESCAPE_REQUEST_HEADER_DEFAULT_NONE
                    || defaultVal == SpringClassName.REQUEST_HEADER_DEFAULT_NONE) {
                defaultVal = ""
            }
        }

        val readParamDefaultValue = readParamDefaultValue(param)

        if (!readParamDefaultValue.isNullOrBlank()) {
            defaultVal = readParamDefaultValue
        }

        if (!required && ruleComputer!!.computer(ClassExportRuleKeys.PARAM_REQUIRED, param) == true) {
            required = true
        }

        if (StringUtils.isBlank(paramName)) {
            paramName = param.name()!!
        }

        if (defaultVal != null) {
            requestHelper!!.addParam(request,
                    paramName!!
                    , defaultVal.toString()
                    , required
                    , ultimateComment)
        } else {
            when {
                request.method == HttpMethod.GET -> addParamAsQuery(param, typeObject, request, ultimateComment)
                typeObject.hasFile() -> addParamAsForm(param, request, typeObject, ultimateComment)
                else -> addParamAsQuery(param, typeObject, request, ultimateComment)
            }

        }

    }

    override fun processMethod(method: ExplicitMethod, kv: KV<String, Any?>, request: Request) {
        super.processMethod(method, kv, request)

        val basePath: String? = kv.getAs("basePath")
        val ctrlHttpMethod: String? = kv.getAs("ctrlHttpMethod")
        val requestMapping = findRequestMappingInAnn(method.psi())
        var httpMethod = findHttpMethod(requestMapping)
        if (httpMethod == HttpMethod.NO_METHOD && ctrlHttpMethod != HttpMethod.NO_METHOD) {
            httpMethod = ctrlHttpMethod!!
        }
        request.method = httpMethod

        val httpPath = contractPath(basePath, findHttpPath(requestMapping))!!
        requestHelper!!.setPath(request, httpPath)
    }

    //region process spring annotation-------------------------------------------------------------------

    private fun findHttpPath(requestMappingAnn: Pair<Map<String, Any?>, String>?): String? {
        val path = requestMappingAnn?.first.any("path", "value")?.tinyString() ?: return null

        return when {
            path.contains(",") -> path.substringBefore(',')
            else -> path
        }
    }

    private fun findHttpMethod(requestMappingAnn: Pair<Map<String, Any?>, String>?): String {
        if (requestMappingAnn != null) {
            when {
                requestMappingAnn.second == SpringClassName.REQUEST_MAPPING_ANNOTATION -> {
                    var method = requestMappingAnn.first["method"].tinyString() ?: return HttpMethod.NO_METHOD
                    if (method.contains(",")) {
                        method = method.substringBefore(",")
                    }
                    return when {
                        StringUtils.isBlank(method) -> {
                            HttpMethod.NO_METHOD
                        }
                        method.startsWith("RequestMethod.") -> {
                            method.removePrefix("RequestMethod.")
                        }
                        method.contains("RequestMethod.") -> {
                            method.substringAfterLast("RequestMethod.")
                        }
                        else -> method
                    }
                }
                requestMappingAnn.second == SpringClassName.GET_MAPPING -> return HttpMethod.GET
                requestMappingAnn.second == SpringClassName.POST_MAPPING -> return HttpMethod.POST
                requestMappingAnn.second == SpringClassName.DELETE_MAPPING -> return HttpMethod.DELETE
                requestMappingAnn.second == SpringClassName.PATCH_MAPPING -> return HttpMethod.PATCH
                requestMappingAnn.second == SpringClassName.PUT_MAPPING -> return HttpMethod.PUT
            }
        }
        return HttpMethod.NO_METHOD
    }

    private fun findRequestMapping(psiClass: PsiClass): Pair<Map<String, Any?>, String>? {
        val requestMappingAnn = findRequestMappingInAnn(psiClass)
        if (requestMappingAnn != null) return requestMappingAnn
        var superCls = psiClass.superClass
        while (superCls != null) {
            val requestMappingAnnInSuper = findRequestMappingInAnn(superCls)
            if (requestMappingAnnInSuper != null) return requestMappingAnnInSuper
            superCls = superCls.superClass
        }
        return null
    }

    private fun findRequestMappingInAnn(ele: PsiElement): Pair<Map<String, Any?>, String>? {
        return SpringClassName.SPRING_REQUEST_MAPPING_ANNOTATIONS
                .map { ann -> annotationHelper!!.findAnnMap(ele, ann)?.to(ann) }
                .firstOrNull { it != null }
    }

    private fun isRequestBody(parameter: PsiParameter): Boolean {
        return annotationHelper!!.hasAnn(parameter, SpringClassName.REQUEST_BODY_ANNOTATION)
    }

    private fun isModelAttr(parameter: PsiParameter): Boolean {
        return annotationHelper!!.hasAnn(parameter, SpringClassName.MODEL_ATTRIBUTE_ANNOTATION)
    }

    private fun findRequestHeader(parameter: PsiParameter): Map<String, Any?>? {
        return annotationHelper!!.findAnnMap(parameter, SpringClassName.REQUEST_HEADER)
    }

    private fun findPathVariable(parameter: PsiParameter): Map<String, Any?>? {
        return annotationHelper!!.findAnnMap(parameter, SpringClassName.PATH_VARIABLE_ANNOTATION)
    }

    private fun findRequestParam(parameter: PsiParameter): Map<String, Any?>? {
        return annotationHelper!!.findAnnMap(parameter, SpringClassName.REQUEST_PARAM_ANNOTATION)
    }

    private fun findParamName(requestParamAnn: Map<String, Any?>?): String? {
        return requestParamAnn.any("name", "value")?.toString()
    }

    private fun findParamRequired(requestParamAnn: Map<String, Any?>): Boolean {
        val required = requestParamAnn["required"]?.toString()
        return when {
            required?.contains("false") == true -> false
            else -> true
        }
    }

    //endregion process spring annotation-------------------------------------------------------------------

}
