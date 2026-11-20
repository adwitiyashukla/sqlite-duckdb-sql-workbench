package com.sqlworkbench.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlSplitterTest {

    @Test
    void splitsOnSemicolons() {
        assertEquals(List.of("select 1", "select 2"), SqlSplitter.split("select 1; select 2"));
    }

    @Test
    void dropsTrailingSemicolon() {
        assertEquals(List.of("select 1"), SqlSplitter.split("select 1;"));
    }

    @Test
    void ignoresEmptyStatements() {
        assertEquals(List.of(), SqlSplitter.split(";;  ;"));
        assertEquals(List.of(), SqlSplitter.split(null));
        assertEquals(List.of(), SqlSplitter.split(""));
    }

    @Test
    void keepsSemicolonInsideStringLiteral() {
        assertEquals(List.of("select 'a;b' as x"), SqlSplitter.split("select 'a;b' as x"));
    }

    @Test
    void handlesDoubledQuoteInsideLiteral() {
        assertEquals(List.of("select 'it''s; fine'"), SqlSplitter.split("select 'it''s; fine'"));
    }

    @Test
    void keepsSemicolonInsideQuotedIdentifier() {
        assertEquals(List.of("select \"weird;name\" from t"), SqlSplitter.split("select \"weird;name\" from t"));
        assertEquals(List.of("select [odd;col] from t"), SqlSplitter.split("select [odd;col] from t"));
        assertEquals(List.of("select `a;b` from t"), SqlSplitter.split("select `a;b` from t"));
    }

    @Test
    void stripsLineComments() {
        assertEquals(List.of("select 1  \nfrom t"), SqlSplitter.split("select 1 -- a; comment\nfrom t"));
    }

    @Test
    void stripsBlockComments() {
        assertEquals(List.of("select   1"), SqlSplitter.split("select /* a; b */ 1"));
    }

    @Test
    void survivesUnterminatedQuoteAndComment() {
        assertEquals(List.of("select 'abc"), SqlSplitter.split("select 'abc"));
        assertEquals(List.of("select 1"), SqlSplitter.split("select 1 /* nope"));
    }

    @Test
    void splitsMultilineScript() {
        List<String> statements = SqlSplitter.split(
                "create table t(a int);\ninsert into t values (1);\nselect * from t;");
        assertEquals(List.of("create table t(a int)", "insert into t values (1)", "select * from t"), statements);
    }
}
