package ${basePackage}.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload de entrada (crear/actualizar) para '${cls.className}'.
 */
public record ${cls.requestDtoName}(
<#list cls.requestDtoFields as f>
<#list f.annotations as a>
        ${a}
</#list>
        ${f.javaType} ${f.javaFieldName}<#if f_has_next>,</#if>
</#list>
) {
}
