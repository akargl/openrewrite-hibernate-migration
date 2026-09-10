package com.akargl.openrewrite.hibernate;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.openrewrite.java.Assertions.java;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseEntityIdInHqlComparisonTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseEntityIdInHqlComparison())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("jakarta.persistence-api", "javax.persistence-api", "hibernate-core",
                                "spring-data-jpa", "spring-data-commons")
                        .addClasspathEntry(Path.of("build/classes/java/test")));
    }

    @Test
    void discoversEntityMetadataFromAReferencedDependencyType() {
        rewriteRun(
          java(
            """
              package example;

              import com.akargl.openrewrite.hibernate.fixture.DependencyEntity;
              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager entityManager, DependencyEntity entity) {
                      entityManager.createQuery("select e from DependencyEntity e where e = 123");
                  }
              }
              """,
            """
              package example;

              import com.akargl.openrewrite.hibernate.fixture.DependencyEntity;
              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager entityManager, DependencyEntity entity) {
                      entityManager.createQuery("select e from DependencyEntity e where e.id = 123");
                  }
              }
              """
          )
        );
    }

    @Test
    void explainsWhenADependencyEntityIsNotReferencedAsAJavaType() {
        rewriteRun(
          spec -> spec.dataTable(HqlQueryAnalysis.Row.class, rows -> {
              assertEquals(1, rows.size());
              assertEquals("UNCHANGED", rows.getFirst().outcome());
              assertTrue(rows.getFirst().reason().contains(
                      "matches dependency type(s) com.akargl.openrewrite.hibernate.fixture.DependencyEntity"),
                      rows.getFirst().reason());
              assertTrue(rows.getFirst().reason().contains("none is referenced as a Java type"),
                      rows.getFirst().reason());
          }),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager entityManager) {
                      entityManager.createQuery("select e from DependencyEntity e where e = 123");
                  }
              }
              """,
            spec -> spec.markers(new JavaSourceSet(
                    UUID.randomUUID(),
                    "main",
                    List.of(JavaType.ShallowClass.build(
                            "com.akargl.openrewrite.hibernate.fixture.DependencyEntity")),
                    Map.of()
            ))
          )
        );
    }

    @Test
    void rewritesEntityAssociationsInsideAConcatenatedQuery() {
        rewriteRun(
          spec -> spec.dataTable(HqlQueryAnalysis.Row.class, rows -> {
              assertEquals(1, rows.size());
              assertEquals("CHANGED", rows.getFirst().outcome());
              assertTrue(rows.getFirst().originalQuery().contains("rule.mode = 3"));
              assertTrue(rows.getFirst().rewrittenQuery().contains("rule.mode.id = 3"));
              assertTrue(rows.getFirst().rewrittenQuery().contains("rule.category.id IN"));
          }),
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;
              import jakarta.persistence.ManyToOne;
              import java.math.BigDecimal;

              @Entity class ServiceRecord { @Id Long id; }
              @Entity class ProductRecord { @Id Long id; }
              @Entity class ModeReference { @Id Integer id; }
              @Entity class CategoryReference { @Id Integer id; }

              @Entity
              class LineItem {
                  @Id Long id;
                  BigDecimal netValue;
                  @ManyToOne ServiceRecord service;
                  @ManyToOne ProductRecord product;
              }

              @Entity
              class PricingRule {
                  @Id Long id;
                  @ManyToOne ModeReference mode;
                  @ManyToOne CategoryReference category;
                  int required;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;
              import java.util.List;

              class Queries {
                  List<Object[]> run(EntityManager entityManager, Long previousServiceId) {
                      return entityManager.createQuery("SELECT rule, item.netValue FROM ServiceRecord service JOIN LineItem item ON service.id = item.service.id JOIN PricingRule rule ON item.product.id = rule.id "
                              + "WHERE rule.mode = 3 AND rule.category IN (99,100,101,131,132,133,134,135,136)AND rule.required = 1 AND service.id =:previousServiceId", Object[].class)
                              .setParameter("previousServiceId", previousServiceId).getResultList();
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;
              import java.util.List;

              class Queries {
                  List<Object[]> run(EntityManager entityManager, Long previousServiceId) {
                      return entityManager.createQuery("SELECT rule, item.netValue FROM ServiceRecord service JOIN LineItem item ON service.id = item.service.id JOIN PricingRule rule ON item.product.id = rule.id "
                              + "WHERE rule.mode.id = 3 AND rule.category.id IN (99,100,101,131,132,133,134,135,136)AND rule.required = 1 AND service.id =:previousServiceId", Object[].class)
                              .setParameter("previousServiceId", previousServiceId).getResultList();
                  }
              }
              """
          )
        );
    }

    @Test
    void rewritesRootAliasComparedWithLiteralAndBoundScalarParameter() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager entityManager) {
                      entityManager.createQuery("select a from MyEntity a where a = 123");
                      entityManager.createQuery("select a from MyEntity a where :id <> a")
                              .setParameter("id", 123L);
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager entityManager) {
                      entityManager.createQuery("select a from MyEntity a where a.id = 123");
                      entityManager.createQuery("select a from MyEntity a where :id <> a.id")
                              .setParameter("id", 123L);
                  }
              }
              """
          )
        );
    }

    @Test
    void rewritesParameterBoundOnLocalQueryVariable() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;
              import jakarta.persistence.Query;

              class Queries {
                  void run(EntityManager entityManager, long id) {
                      Query query = entityManager.createQuery("select a from MyEntity a where a = :id");
                      query.setParameter("id", id);
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;
              import jakarta.persistence.Query;

              class Queries {
                  void run(EntityManager entityManager, long id) {
                      Query query = entityManager.createQuery("select a from MyEntity a where a.id = :id");
                      query.setParameter("id", id);
                  }
              }
              """
          )
        );
    }

    @Test
    void skipsUnboundEntityTypedMismatchedAndAmbiguousParameters() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;
              import jakarta.persistence.Query;

              class Queries {
                  void run(EntityManager entityManager, MyEntity entity) {
                      entityManager.createQuery("select a from MyEntity a where a = :unbound");
                      entityManager.createQuery("select a from MyEntity a where a = :entity")
                              .setParameter("entity", entity);
                      entityManager.createQuery("select a from MyEntity a where a = :wrongType")
                              .setParameter("wrongType", "123");

                      Query ambiguous = entityManager.createQuery(
                              "select a from MyEntity a where a = :ambiguous"
                      );
                      ambiguous.setParameter("ambiguous", 123L);
                      ambiguous.setParameter("ambiguous", "123");

                      Query reassigned = entityManager.createQuery(
                              "select a from MyEntity a where a = :reassigned"
                      );
                      reassigned = entityManager.createQuery("select a from MyEntity a where a.id = :reassigned");
                      reassigned.setParameter("reassigned", 123L);
                  }
              }
              """
          )
        );
    }

    @Test
    void rewritesInPredicatesForCompatibleCollectionsArraysAndScalarLists() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;
              import java.util.List;

              class Queries {
                  void run(EntityManager entityManager, List<Long> ids, Long[] idArray) {
                      entityManager.createQuery("select a from MyEntity a where a in (:ids)")
                              .setParameter("ids", ids);
                      entityManager.createQuery("select a from MyEntity a where a in :ids")
                              .setParameter("ids", ids);
                      entityManager.createQuery("select a from MyEntity a where a not in (:ids)")
                              .setParameter("ids", idArray);
                      entityManager.createQuery("select a from MyEntity a where a in (1, 2)");
                      entityManager.createQuery("select a from MyEntity a where a in (:first, :second)")
                              .setParameter("first", 1L)
                              .setParameter("second", 2L);
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;
              import java.util.List;

              class Queries {
                  void run(EntityManager entityManager, List<Long> ids, Long[] idArray) {
                      entityManager.createQuery("select a from MyEntity a where a.id in (:ids)")
                              .setParameter("ids", ids);
                      entityManager.createQuery("select a from MyEntity a where a.id in :ids")
                              .setParameter("ids", ids);
                      entityManager.createQuery("select a from MyEntity a where a.id not in (:ids)")
                              .setParameter("ids", idArray);
                      entityManager.createQuery("select a from MyEntity a where a.id in (1, 2)");
                      entityManager.createQuery("select a from MyEntity a where a.id in (:first, :second)")
                              .setParameter("first", 1L)
                              .setParameter("second", 2L);
                  }
              }
              """
          )
        );
    }

    @Test
    void supportsHibernateSetParameterList() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import java.util.List;
              import org.hibernate.Session;

              class Queries {
                  void run(Session session, List<Long> ids, List rawIds) {
                      session.createQuery("select a from MyEntity a where a in (:ids)")
                              .setParameterList("ids", ids);
                      session.createQuery("select a from MyEntity a where a in (:rawIds)")
                              .setParameterList("rawIds", rawIds, Long.class);
                  }
              }
              """,
            """
              package example;

              import java.util.List;
              import org.hibernate.Session;

              class Queries {
                  void run(Session session, List<Long> ids, List rawIds) {
                      session.createQuery("select a from MyEntity a where a.id in (:ids)")
                              .setParameterList("ids", ids);
                      session.createQuery("select a from MyEntity a where a.id in (:rawIds)")
                              .setParameterList("rawIds", rawIds, Long.class);
                  }
              }
              """
          )
        );
    }

    @Test
    @SuppressWarnings("rawtypes")
    void skipsEntityWrongRawAndUnboundCollectionParameters() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;
              import java.util.List;

              class Queries {
                  void run(EntityManager entityManager, List<MyEntity> entities, List<String> names, List raw) {
                      entityManager.createQuery("select a from MyEntity a where a in (:entities)")
                              .setParameter("entities", entities);
                      entityManager.createQuery("select a from MyEntity a where a in (:names)")
                              .setParameter("names", names);
                      entityManager.createQuery("select a from MyEntity a where a in (:raw)")
                              .setParameter("raw", raw);
                      entityManager.createQuery("select a from MyEntity a where a in (:unbound)");
                  }
              }
              """
          )
        );
    }

    @Test
    void usesConfiguredEntityNameAndIdentifierProperty() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity(name = "CustomerRecord")
              class Customer {
                  private String customerNumber;

                  @Id
                  public String getCustomerNumber() {
                      return customerNumber;
                  }
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.NamedQuery;

              @NamedQuery(name = "customer", query = "from CustomerRecord c where c = 'number'")
              class CustomerQueries {
              }
              """,
            """
              package example;

              import jakarta.persistence.NamedQuery;

              @NamedQuery(name = "customer", query = "from CustomerRecord c where c.customerNumber = 'number'")
              class CustomerQueries {
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.NamedQuery;

              @NamedQuery(name = "unresolved", query = "from CustomerRecord c where c = :number")
              class UnresolvedCustomerQueries {
              }
              """
          )
        );
    }

    @Test
    void rewritesEntityValuedAssociationAndJoinedAlias() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class Customer {
                  @Id
                  String id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class PurchaseOrder {
                  @Id
                  Long id;
                  Customer customer;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from PurchaseOrder o where o.customer = :customerId")
                              .setParameter("customerId", "abc");
                      em.createQuery("select o from PurchaseOrder o join o.customer c where c = 'abc'");
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from PurchaseOrder o where o.customer.id = :customerId")
                              .setParameter("customerId", "abc");
                      em.createQuery("select o from PurchaseOrder o join o.customer c where c.id = 'abc'");
                  }
              }
              """
          )
        );
    }

    @Test
    void findsIdentifierOnMappedSuperclass() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Id;
              import jakarta.persistence.MappedSuperclass;

              @MappedSuperclass
              class BaseEntity {
                  @Id
                  Long key;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.Entity;

              @Entity
              class ChildEntity extends BaseEntity {
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from ChildEntity c where c = ?1")
                              .setParameter(1, 1L);
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from ChildEntity c where c.key = ?1")
                              .setParameter(1, 1L);
                  }
              }
              """
          )
        );
    }

    @Test
    void supportsLegacyJavaxPersistence() {
        rewriteRun(
          java(
            """
              package example;

              import javax.persistence.Entity;
              import javax.persistence.Id;

              @Entity
              class LegacyEntity {
                  @Id
                  Long legacyId;
              }
              """
          ),
          java(
            """
              package example;

              import javax.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from LegacyEntity e where e = 1");
                  }
              }
              """,
            """
              package example;

              import javax.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from LegacyEntity e where e.legacyId = 1");
                  }
              }
              """
          )
        );
    }

    @Test
    void supportsSpringDataQueryAndSkipsNativeQuery() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class Account {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import java.util.List;
              import org.springframework.data.jpa.repository.Query;
              import org.springframework.data.repository.query.Param;

              interface AccountRepository {
                  boolean NATIVE = true;

                  @Query("from Account a where a = :accountId")
                  Object findOne(@Param("accountId") long id);

                  @Query("from Account a where a = :account")
                  Object findByEntity(Account account);

                  @Query("from Account a where a in (:ids)")
                  Object findAllByIdIn(List<Long> ids);

                  @Query("from Account a where a in (?1)")
                  Object findAllByIdInPositionally(List<Long> ids);

                  @Query(value = "select * from account a where a = 1", nativeQuery = true)
                  Object nativeQuery();

                  @Query(value = "select * from account a where a = 1", nativeQuery = NATIVE)
                  Object nativeQueryConstant();
              }
              """,
            """
              package example;

              import java.util.List;
              import org.springframework.data.jpa.repository.Query;
              import org.springframework.data.repository.query.Param;

              interface AccountRepository {
                  boolean NATIVE = true;

                  @Query("from Account a where a.id = :accountId")
                  Object findOne(@Param("accountId") long id);

                  @Query("from Account a where a = :account")
                  Object findByEntity(Account account);

                  @Query("from Account a where a.id in (:ids)")
                  Object findAllByIdIn(List<Long> ids);

                  @Query("from Account a where a.id in (?1)")
                  Object findAllByIdInPositionally(List<Long> ids);

                  @Query(value = "select * from account a where a = 1", nativeQuery = true)
                  Object nativeQuery();

                  @Query(value = "select * from account a where a = 1", nativeQuery = NATIVE)
                  Object nativeQueryConstant();
              }
              """
          )
        );
    }

    @Test
    void preservesTextBlockFormatting() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class TextBlockEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery(\"""
                              select e
                              from TextBlockEntity e
                              where e = :id
                              \""")
                              .setParameter("id", 1L);
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery(\"""
                              select e
                              from TextBlockEntity e
                              where e.id = :id
                              \""")
                              .setParameter("id", 1L);
                  }
              }
              """
          )
        );
    }

    @Test
    void skipsAlreadyCorrectEntityComparisonsUnknownStringsAndCompositeIds() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.EmbeddedId;
              import jakarta.persistence.Id;
              import jakarta.persistence.IdClass;

              @Entity
              class SimpleEntity {
                  @Id
                  Long id;
                  String name;
              }

              @Entity
              class CompositeEntity {
                  @EmbeddedId
                  CompositeKey key;
              }

              class CompositeKey {
                  long first;
                  long second;
              }

              @Entity
              @IdClass(LegacyCompositeKey.class)
              class IdClassEntity {
                  @Id
                  long first;
                  @Id
                  long second;
              }

              class LegacyCompositeKey {
                  long first;
                  long second;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em, String dynamic) {
                      em.createQuery("from SimpleEntity a where a.id = 1");
                      em.createQuery("from SimpleEntity a, SimpleEntity b where a = b");
                      em.createQuery("from SimpleEntity a where a.name = 'name'");
                      em.createQuery("from CompositeEntity c where c = 1");
                      em.createQuery("from IdClassEntity c where c = 1");
                      em.createNativeQuery("select * from simple_entity where id = 1");
                      em.createQuery("from SimpleEntity a where a = " + dynamic);
                      String unrelated = "from SimpleEntity a where a = 1";
                  }
              }
              """
          )
        );
    }

    @Test
    void skipsQueriesWithNestedAliasScopes() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class OuterEntity {
                  @Id
                  Long id;
              }

              @Entity
              class InnerEntity {
                  @Id
                  String code;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from OuterEntity e where e = 1 and exists (select e from InnerEntity e where e = 'x')");
                      em.createQuery("from OuterEntity e where e = 1 union from OuterEntity e where e = 2");
                      em.createQuery("from OuterEntity e where e =");
                  }
              }
              """
          )
        );
    }

    @Test
    void rewritesMultiplePredicatesAndMutationQueries() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
                  String name;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;
              import org.hibernate.Session;

              class Queries {
                  void run(EntityManager entityManager, Session session) {
                      entityManager.createQuery("from MyEntity a where a = 1 or 2 <> a");
                      entityManager.createQuery("update MyEntity a set a.name = 'updated' where a = 3");
                      session.createSelectionQuery("from example.MyEntity a where a = 5", MyEntity.class);
                      session.createMutationQuery("delete from MyEntity a where a = :id")
                              .setParameter("id", 4L);
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;
              import org.hibernate.Session;

              class Queries {
                  void run(EntityManager entityManager, Session session) {
                      entityManager.createQuery("from MyEntity a where a.id = 1 or 2 <> a.id");
                      entityManager.createQuery("update MyEntity a set a.name = 'updated' where a.id = 3");
                      session.createSelectionQuery("from example.MyEntity a where a.id = 5", MyEntity.class);
                      session.createMutationQuery("delete from MyEntity a where a.id = :id")
                              .setParameter("id", 4L);
                  }
              }
              """
          )
        );
    }

    @Test
    void rewritesPositionalArrayAndCovariantCollectionParameters() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;
              import java.util.Collection;
              import java.util.List;

              class Queries {
                  void run(EntityManager entityManager, List<Long> ids, long[] idArray,
                           Collection<? extends Long> covariantIds) {
                      entityManager.createQuery("from MyEntity a where a in (?1)")
                              .setParameter(1, ids);
                      entityManager.createQuery("from MyEntity a where a in (:array)")
                              .setParameter("array", idArray);
                      entityManager.createQuery("from MyEntity a where a in (:covariant)")
                              .setParameter("covariant", covariantIds);
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;
              import java.util.Collection;
              import java.util.List;

              class Queries {
                  void run(EntityManager entityManager, List<Long> ids, long[] idArray,
                           Collection<? extends Long> covariantIds) {
                      entityManager.createQuery("from MyEntity a where a.id in (?1)")
                              .setParameter(1, ids);
                      entityManager.createQuery("from MyEntity a where a.id in (:array)")
                              .setParameter("array", idArray);
                      entityManager.createQuery("from MyEntity a where a.id in (:covariant)")
                              .setParameter("covariant", covariantIds);
                  }
              }
              """
          )
        );
    }

    @Test
    void usesExplicitParameterTypeForNullHibernateBinding() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import org.hibernate.Session;

              class Queries {
                  void run(Session session) {
                      session.createQuery("from MyEntity a where a = :id")
                              .setParameter("id", null, Long.class);
                  }
              }
              """,
            """
              package example;

              import org.hibernate.Session;

              class Queries {
                  void run(Session session) {
                      session.createQuery("from MyEntity a where a.id = :id")
                              .setParameter("id", null, Long.class);
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesNullAndMixedEntityValuedComparisons() {
        rewriteRun(
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;
              import java.util.List;

              class Queries {
                  void run(EntityManager entityManager, MyEntity entity, List<MyEntity> entities, long id) {
                      entityManager.createQuery("from MyEntity a where a = null");
                      entityManager.createQuery("from MyEntity a where null <> a");
                      entityManager.createQuery("from MyEntity a where a = :entity")
                              .setParameter("entity", entity);
                      entityManager.createQuery("from MyEntity a where a in (:entities)")
                              .setParameter("entities", entities);
                      entityManager.createQuery("from MyEntity a where a in (:id, :entity)")
                              .setParameter("id", id)
                              .setParameter("entity", entity);
                  }
              }
              """
          )
        );
    }

    @Test
    void reportsEveryInspectedQueryWithItsOutcome() {
        rewriteRun(
          spec -> spec.dataTable(HqlQueryAnalysis.Row.class, rows -> {
              assertEquals(3, rows.size());

              HqlQueryAnalysis.Row changed = rows.stream()
                      .filter(row -> row.originalQuery().contains("a = 1"))
                      .findFirst()
                      .orElseThrow();
              assertEquals("CHANGED", changed.outcome());
              assertEquals("from MyEntity a where a.id = 1", changed.rewrittenQuery());

              HqlQueryAnalysis.Row unbound = rows.stream()
                      .filter(row -> row.originalQuery().contains(":unbound"))
                      .findFirst()
                      .orElseThrow();
              assertEquals("UNCHANGED", unbound.outcome());
              assertTrue(unbound.reason().contains("unbound, ambiguous, or does not match"));

              HqlQueryAnalysis.Row invalid = rows.stream()
                      .filter(row -> row.originalQuery().endsWith("a ="))
                      .findFirst()
                      .orElseThrow();
              assertEquals("SKIPPED", invalid.outcome());
              assertTrue(invalid.reason().startsWith("Invalid HQL:"));
          }),
          java(
            """
              package example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              class MyEntity {
                  @Id
                  Long id;
              }
              """
          ),
          java(
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager entityManager) {
                      entityManager.createQuery("from MyEntity a where a = 1");
                      entityManager.createQuery("from MyEntity a where a = :unbound");
                      entityManager.createQuery("from MyEntity a where a =");
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager entityManager) {
                      entityManager.createQuery("from MyEntity a where a.id = 1");
                      entityManager.createQuery("from MyEntity a where a = :unbound");
                      entityManager.createQuery("from MyEntity a where a =");
                  }
              }
              """
          )
        );
    }
}
