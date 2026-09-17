package ${basePackage}.repository;

import ${basePackage}.domain.model.${cls.className};
import org.springframework.data.jpa.repository.JpaRepository;

public interface ${cls.repositoryName} extends JpaRepository<${cls.className}, ${cls.effectivePrimaryKeyType}> {
}
