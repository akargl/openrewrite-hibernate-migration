package com.akargl.openrewrite.hibernate.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class DependencyEntity {
    @Id
    private Long id;
}
