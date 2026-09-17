package ${basePackage}.domain.model;

import jakarta.persistence.*;
import java.util.*;

/**
 * Entidad JPA generada a partir de la clase '${cls.className}' del diagrama UML/ER.
 */
@Entity
<#if cls.inheritanceRoot>
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
</#if>
<#if !cls.child>
@Table(name = ${cls.tableAnnotationLiteral})
</#if>
public class ${cls.className}<#if cls.child> extends ${cls.parentClassName}</#if> {

<#if cls.primaryKeyField??>
    @Id
<#if cls.primaryKeyField.javaFieldName == "id" && cls.primaryKeyField.javaType == "Long">
    @GeneratedValue(strategy = GenerationType.IDENTITY)
</#if>
    @Column(name = ${cls.primaryKeyField.columnAnnotationLiteral})
    private ${cls.primaryKeyField.javaType} ${cls.primaryKeyField.javaFieldName};

</#if>
<#list cls.fields as f>
    @Column(name = ${f.columnAnnotationLiteral}<#if !f.nullable>, nullable = false</#if><#if f.unique>, unique = true</#if><#if f.varchar>, length = ${f.length?c}</#if>)
    private ${f.javaType} ${f.javaFieldName};

</#list>
<#list cls.relationshipFields as rf>
<#if rf.kind == "MANY_TO_ONE">
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "${rf.joinColumnName}"<#if !rf.nullable>, nullable = false</#if>)
    private ${rf.targetClassName} ${rf.fieldName};

<#elseif rf.kind == "ONE_TO_MANY">
    @OneToMany(mappedBy = "${rf.mappedBy}", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<${rf.targetClassName}> ${rf.fieldName} = new ArrayList<>();

<#elseif rf.kind == "ONE_TO_ONE_OWNING">
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "${rf.joinColumnName}")
    private ${rf.targetClassName} ${rf.fieldName};

<#elseif rf.kind == "ONE_TO_ONE_MAPPED">
    @OneToOne(mappedBy = "${rf.mappedBy}")
    private ${rf.targetClassName} ${rf.fieldName};

<#elseif rf.kind == "MANY_TO_MANY_OWNING">
    @ManyToMany
    @JoinTable(name = "${rf.joinTableName}",
            joinColumns = @JoinColumn(name = "${rf.joinColumnOwn}"),
            inverseJoinColumns = @JoinColumn(name = "${rf.joinColumnOther}"))
    private List<${rf.targetClassName}> ${rf.fieldName} = new ArrayList<>();

<#elseif rf.kind == "MANY_TO_MANY_MAPPED">
    @ManyToMany(mappedBy = "${rf.mappedBy}")
    private List<${rf.targetClassName}> ${rf.fieldName} = new ArrayList<>();

</#if>
</#list>
    public ${cls.className}() {
    }

<#if cls.primaryKeyField??>
    public ${cls.primaryKeyField.javaType} ${cls.primaryKeyField.getterName}() {
        return ${cls.primaryKeyField.javaFieldName};
    }

    public void ${cls.primaryKeyField.setterName}(${cls.primaryKeyField.javaType} ${cls.primaryKeyField.javaFieldName}) {
        this.${cls.primaryKeyField.javaFieldName} = ${cls.primaryKeyField.javaFieldName};
    }

</#if>
<#list cls.fields as f>
    public ${f.javaType} ${f.getterName}() {
        return ${f.javaFieldName};
    }

    public void ${f.setterName}(${f.javaType} ${f.javaFieldName}) {
        this.${f.javaFieldName} = ${f.javaFieldName};
    }

</#list>
<#list cls.relationshipFields as rf>
    public <#if rf.collection>List<${rf.targetClassName}><#else>${rf.targetClassName}</#if> get${rf.capitalizedFieldName}() {
        return ${rf.fieldName};
    }

    public void set${rf.capitalizedFieldName}(<#if rf.collection>List<${rf.targetClassName}><#else>${rf.targetClassName}</#if> ${rf.fieldName}) {
        this.${rf.fieldName} = ${rf.fieldName};
    }

</#list>
}
