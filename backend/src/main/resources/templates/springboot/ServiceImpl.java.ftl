package ${basePackage}.service.impl;

import ${basePackage}.domain.model.${cls.className};
<#list cls.owningSingleValuedRelationships as rf>
import ${basePackage}.domain.model.${rf.targetClassName};
import ${basePackage}.repository.${rf.targetClassName}Repository;
</#list>
import ${basePackage}.repository.${cls.repositoryName};
import ${basePackage}.service.${cls.serviceName};
import ${basePackage}.service.dto.${cls.requestDtoName};
import ${basePackage}.service.dto.${cls.responseDtoName};
import ${basePackage}.service.dto.mapper.${cls.mapperName};
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * CRUD real de '${cls.className}' sobre {@link ${cls.repositoryName}}, más un stub
 * por cada método de negocio del diagrama UML (invariante 5, sección 13.2 del plan
 * arquitectónico): el cuerpo generado deja explícito que falta implementar la
 * lógica real, sin dejar de compilar.
 */
@Service
@Transactional
public class ${cls.serviceImplName} implements ${cls.serviceName} {

    private static final Logger log = LoggerFactory.getLogger(${cls.serviceImplName}.class);

    private final ${cls.repositoryName} repository;
    private final ${cls.mapperName} mapper = new ${cls.mapperName}();
<#list cls.owningSingleValuedRelationships as rf>
    private final ${rf.targetClassName}Repository ${rf.fieldName}Repository;
</#list>

    public ${cls.serviceImplName}(${cls.repositoryName} repository<#list cls.owningSingleValuedRelationships as rf>, ${rf.targetClassName}Repository ${rf.fieldName}Repository</#list>) {
        this.repository = repository;
<#list cls.owningSingleValuedRelationships as rf>
        this.${rf.fieldName}Repository = ${rf.fieldName}Repository;
</#list>
    }

    @Override
    public ${cls.responseDtoName} create(${cls.requestDtoName} request) {
        ${cls.className} entity = mapper.toEntity(request);
<#list cls.owningSingleValuedRelationships as rf>
        if (request.${rf.fieldName}Id() != null) {
            ${rf.targetClassName} ${rf.fieldName} = ${rf.fieldName}Repository.findById(request.${rf.fieldName}Id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "${rf.targetClassName} no encontrado: " + request.${rf.fieldName}Id()));
            entity.set${rf.capitalizedFieldName}(${rf.fieldName});
        }
</#list>
        ${cls.className} saved = repository.save(entity);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ${cls.responseDtoName} findById(${cls.effectivePrimaryKeyType} id) {
        ${cls.className} entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "${cls.className} no encontrado: " + id));
        return mapper.toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<${cls.responseDtoName}> findAll() {
        return repository.findAll().stream().map(mapper::toResponse).toList();
    }

    @Override
    public ${cls.responseDtoName} update(${cls.effectivePrimaryKeyType} id, ${cls.requestDtoName} request) {
        ${cls.className} entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "${cls.className} no encontrado: " + id));
        mapper.updateEntity(entity, request);
<#list cls.owningSingleValuedRelationships as rf>
        if (request.${rf.fieldName}Id() != null) {
            ${rf.targetClassName} ${rf.fieldName} = ${rf.fieldName}Repository.findById(request.${rf.fieldName}Id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "${rf.targetClassName} no encontrado: " + request.${rf.fieldName}Id()));
            entity.set${rf.capitalizedFieldName}(${rf.fieldName});
        } else {
            entity.set${rf.capitalizedFieldName}(null);
        }
</#list>
        ${cls.className} saved = repository.save(entity);
        return mapper.toResponse(saved);
    }

    @Override
    public void delete(${cls.effectivePrimaryKeyType} id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "${cls.className} no encontrado: " + id);
        }
        repository.deleteById(id);
    }

<#list cls.methods as m>
    @Override
    public ${m.returnType} ${m.name}(${m.parameterList}) {
        // TODO: Implementar lógica de negocio definida en diagrama UML
<#if (m.parameters?size > 0)>
        log.info("Ejecutando stub ${m.name}: ${m.logPlaceholders}"${m.logArgs});
<#else>
        log.info("Ejecutando stub ${m.name}");
</#if>
<#if m.returnType != "void">
        throw new UnsupportedOperationException("Metodo de negocio '${m.name}' aun no implementado");
</#if>
    }

</#list>
}
