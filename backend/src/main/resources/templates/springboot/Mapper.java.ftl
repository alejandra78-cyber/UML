package ${basePackage}.service.dto.mapper;

import ${basePackage}.domain.model.${cls.className};
import ${basePackage}.service.dto.${cls.requestDtoName};
import ${basePackage}.service.dto.${cls.responseDtoName};

/**
 * Mapeo desacoplado entre la entidad '${cls.className}' y sus DTOs. Las relaciones
 * dueñas ({@code @ManyToOne}/{@code @OneToOne}) NO se resuelven aquí: el service
 * las resuelve a partir del id recibido en el request DTO (ver '${cls.serviceImplName}'),
 * para no acoplar este mapper a los repositorios de las clases relacionadas.
 *
 * <p>Simplificación deliberada: {@code toResponse} asume que el identificador del
 * lado relacionado se expone como {@code getId()} (cierto para toda PK inyectada
 * automáticamente, y para cualquier PK manual literalmente llamada "id").</p>
 */
public class ${cls.mapperName} {

    public ${cls.className} toEntity(${cls.requestDtoName} dto) {
        ${cls.className} entity = new ${cls.className}();
        updateEntity(entity, dto);
        return entity;
    }

    public void updateEntity(${cls.className} entity, ${cls.requestDtoName} dto) {
<#list cls.fields as f>
        entity.${f.setterName}(dto.${f.javaFieldName}());
</#list>
    }

    public ${cls.responseDtoName} toResponse(${cls.className} entity) {
        return new ${cls.responseDtoName}(
<#if cls.primaryKeyField??>
                entity.${cls.primaryKeyField.getterName}()<#if (cls.fields?size > 0) || (cls.owningSingleValuedRelationships?size > 0)>,</#if>
</#if>
<#list cls.fields as f>
                entity.${f.getterName}()<#if f_has_next || (cls.owningSingleValuedRelationships?size > 0)>,</#if>
</#list>
<#list cls.owningSingleValuedRelationships as rf>
                (entity.get${rf.capitalizedFieldName}() == null ? null : entity.get${rf.capitalizedFieldName}().getId())<#if rf_has_next>,</#if>
</#list>
        );
    }
}
