package ${basePackage}.service.dto;

/**
 * Payload de salida para '${cls.className}'.
 */
public record ${cls.responseDtoName}(
<#list cls.responseDtoFields as f>
        ${f.javaType} ${f.javaFieldName}<#if f_has_next>,</#if>
</#list>
) {
}
