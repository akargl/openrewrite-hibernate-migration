package com.akargl.openrewrite.hibernate;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * Reports every static HQL/JPQL query inspected by {@link UseEntityIdInHqlComparison}.
 */
final class HqlQueryAnalysis extends DataTable<HqlQueryAnalysis.Row> {
    HqlQueryAnalysis(Recipe recipe) {
        super(recipe, "HQL entity comparison analysis",
                "Static HQL/JPQL queries inspected by the recipe, including unchanged queries and the reason.");
    }

    record Row(
            @Column(displayName = "Source path", description = "Source file containing the query.")
            String sourcePath,
            @Column(displayName = "Query source", description = "API method or annotation containing the query.")
            String querySource,
            @Column(displayName = "Outcome", description = "Whether the query was changed, unchanged, or skipped.")
            String outcome,
            @Column(displayName = "Reason", description = "Why the query was changed, unchanged, or skipped.")
            String reason,
            @Column(displayName = "Original query", description = "Query text before the recipe ran.")
            String originalQuery,
            @Column(displayName = "Rewritten query", description = "Query text after analysis.")
            String rewrittenQuery
    ) {
    }
}
