package org.openrewrite.contrib.hibernate;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseEntityIdInHqlComparisonTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseEntityIdInHqlComparison())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("jakarta.persistence-api", "javax.persistence-api", "hibernate-core", "spring-data-jpa"));
    }

    @Test
    void rewritesRootAliasComparedWithLiteralAndParameter() {
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
                      entityManager.createQuery("select a from MyEntity a where :id <> a");
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager entityManager) {
                      entityManager.createQuery("select a from MyEntity a where a.id = 123");
                      entityManager.createQuery("select a from MyEntity a where :id <> a.id");
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

              @NamedQuery(name = "customer", query = "from CustomerRecord c where c = :number")
              class CustomerQueries {
              }
              """,
            """
              package example;

              import jakarta.persistence.NamedQuery;

              @NamedQuery(name = "customer", query = "from CustomerRecord c where c.customerNumber = :number")
              class CustomerQueries {
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
                      em.createQuery("from PurchaseOrder o where o.customer = :customerId");
                      em.createQuery("select o from PurchaseOrder o join o.customer c where c = 'abc'");
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from PurchaseOrder o where o.customer.id = :customerId");
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
                      em.createQuery("from ChildEntity c where c = ?1");
                  }
              }
              """,
            """
              package example;

              import jakarta.persistence.EntityManager;

              class Queries {
                  void run(EntityManager em) {
                      em.createQuery("from ChildEntity c where c.key = ?1");
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

              import org.springframework.data.jpa.repository.Query;

              interface AccountRepository {
                  boolean NATIVE = true;

                  @Query("from Account a where a = :id")
                  Object findOne(long id);

                  @Query(value = "select * from account a where a = 1", nativeQuery = true)
                  Object nativeQuery();

                  @Query(value = "select * from account a where a = 1", nativeQuery = NATIVE)
                  Object nativeQueryConstant();
              }
              """,
            """
              package example;

              import org.springframework.data.jpa.repository.Query;

              interface AccountRepository {
                  boolean NATIVE = true;

                  @Query("from Account a where a.id = :id")
                  Object findOne(long id);

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
                              \""");
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
                              \""");
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
                  }
              }
              """
          )
        );
    }
}
