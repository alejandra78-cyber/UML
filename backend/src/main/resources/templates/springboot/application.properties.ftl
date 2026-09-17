<#assign dbName = projectName?replace("-", "_")>
spring.application.name=${projectName}

spring.datasource.url=jdbc:postgresql://localhost:5432/${dbName}
spring.datasource.username=app
spring.datasource.password=app
spring.datasource.driver-class-name=org.postgresql.Driver

# Sin Flyway/Liquibase en el backend generado: Hibernate mantiene el esquema
# sincronizado con las entidades. Reemplazar por migraciones versionadas antes de
# usar esto en produccion real (mismo criterio que el backend de ModelCollab).
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
