package com.sqlworkbench.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTokensTest {

    private static String kinds(String sql) {
        StringBuilder out = new StringBuilder();
        for (SqlTokens.Token token : SqlTokens.tokenize(sql)) {
            out.append(token.kind()).append(':').append(sql, token.start(), token.end()).append(' ');
        }
        return out.toString().trim();
    }

    @Test
    void marksKeywordsInAnyCase() {
        assertEquals("KEYWORD:SELECT KEYWORD:from", kinds("SELECT name from people"));
    }

    @Test
    void marksStringsNumbersAndComments() {
        assertEquals("KEYWORD:select STRING:'it''s' NUMBER:42 COMMENT:-- note",
                kinds("select 'it''s', 42 -- note"));
        assertEquals("COMMENT:/* a */ KEYWORD:select NUMBER:1.5", kinds("/* a */ select 1.5"));
    }

    @Test
    void skipsQuotedIdentifiersAndPlainNames() {
        assertEquals("KEYWORD:select KEYWORD:from", kinds("select \"select\", t1 from [order]"));
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertEquals(List.of(), SqlTokens.tokenize(""));
        assertEquals(List.of(), SqlTokens.tokenize(null));
    }

    @Test
    void tokensDoNotOverlap() {
        String sql = "select a, 'x' from t where b > 10 -- end\n/* c */ order by a";
        int previousEnd = 0;
        for (SqlTokens.Token token : SqlTokens.tokenize(sql)) {
            assertTrue(token.start() >= previousEnd);
            assertTrue(token.end() > token.start());
            previousEnd = token.end();
        }
    }

    @Test
    void detectsReadOnlyStatements() {
        assertTrue(SqlTokens.isReadOnly("select * from t"));
        assertTrue(SqlTokens.isReadOnly("  with x as (select 1) select * from x"));
        assertTrue(SqlTokens.isReadOnly("pragma table_info(t)"));
        assertFalse(SqlTokens.isReadOnly("insert into t values (1) returning *"));
        assertFalse(SqlTokens.isReadOnly("with x as (select 1) insert into t select * from x returning *"));
        assertFalse(SqlTokens.isReadOnly("delete from t returning id"));
        assertFalse(SqlTokens.isReadOnly(""));
    }
}
