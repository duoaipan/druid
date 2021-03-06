/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql.parser;

import java.util.List;

import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlOrderingExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.util.FnvHash;
import com.alibaba.druid.util.JdbcConstants;

public class SQLSelectParser extends SQLParser {
    protected SQLExprParser exprParser;

    public SQLSelectParser(String sql){
        super(sql);
    }

    public SQLSelectParser(Lexer lexer){
        super(lexer);
    }

    public SQLSelectParser(SQLExprParser exprParser){
        super(exprParser.getLexer(), exprParser.getDbType());
        this.exprParser = exprParser;
    }

    public SQLSelect select() {
        SQLSelect select = new SQLSelect();

        if (lexer.token == Token.WITH) {
            SQLWithSubqueryClause with = this.parseWith();
            select.setWithSubQuery(with);
        }

        SQLSelectQuery query = query();
        select.setQuery(query);

        SQLOrderBy orderBy = this.parseOrderBy();

        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) query;

            if (queryBlock.getOrderBy() == null) {
                queryBlock.setOrderBy(orderBy);
            } else {
                select.setOrderBy(orderBy);
            }

            if (orderBy != null) {
                parseFetchClause(queryBlock);
            }
        } else {
            select.setOrderBy(orderBy);
        }

        while (lexer.token == Token.HINT) {
            this.exprParser.parseHints(select.getHints());
        }

        return select;
    }

    protected SQLUnionQuery createSQLUnionQuery() {
        return new SQLUnionQuery();
    }

    public SQLUnionQuery unionRest(SQLUnionQuery union) {
        if (lexer.token == Token.ORDER) {
            SQLOrderBy orderBy = this.exprParser.parseOrderBy();
            union.setOrderBy(orderBy);
            return unionRest(union);
        }
        return union;
    }

    public SQLSelectQuery queryRest(SQLSelectQuery selectQuery) {
        if (lexer.token == Token.UNION) {
            lexer.nextToken();

            SQLUnionQuery union = createSQLUnionQuery();
            union.setLeft(selectQuery);

            if (lexer.token == Token.ALL) {
                union.setOperator(SQLUnionOperator.UNION_ALL);
                lexer.nextToken();
            } else if (lexer.token == Token.DISTINCT) {
                union.setOperator(SQLUnionOperator.DISTINCT);
                lexer.nextToken();
            }
            SQLSelectQuery right = this.query();
            union.setRight(right);

            return unionRest(union);
        }

        if (lexer.token == Token.EXCEPT) {
            lexer.nextToken();

            SQLUnionQuery union = new SQLUnionQuery();
            union.setLeft(selectQuery);

            union.setOperator(SQLUnionOperator.EXCEPT);

            SQLSelectQuery right = this.query();
            union.setRight(right);

            return union;
        }

        if (lexer.token == Token.INTERSECT) {
            lexer.nextToken();

            SQLUnionQuery union = new SQLUnionQuery();
            union.setLeft(selectQuery);

            union.setOperator(SQLUnionOperator.INTERSECT);

            SQLSelectQuery right = this.query();
            union.setRight(right);

            return union;
        }

        if (lexer.token == Token.MINUS) {
            lexer.nextToken();

            SQLUnionQuery union = new SQLUnionQuery();
            union.setLeft(selectQuery);

            union.setOperator(SQLUnionOperator.MINUS);

            SQLSelectQuery right = this.query();
            union.setRight(right);

            return union;
        }

        return selectQuery;
    }

    public SQLSelectQuery query() {
        if (lexer.token == Token.LPAREN) {
            lexer.nextToken();

            SQLSelectQuery select = query();
            accept(Token.RPAREN);

            return queryRest(select);
        }
        
        SQLSelectQueryBlock queryBlock = new SQLSelectQueryBlock();
        
        if (lexer.hasComment() && lexer.isKeepComments()) {
            queryBlock.addBeforeComment(lexer.readAndResetComments());
        }

        accept(Token.SELECT);

        if (lexer.token == Token.COMMENT) {
            lexer.nextToken();
        }

        if (JdbcConstants.INFORMIX.equals(dbType)) {
            if (lexer.identifierEquals(FnvHash.Constants.SKIP)) {
                lexer.nextToken();
                SQLExpr offset = this.exprParser.primary();
                queryBlock.setOffset(offset);
            }

            if (lexer.identifierEquals(FnvHash.Constants.FIRST)) {
                lexer.nextToken();
                SQLExpr first = this.exprParser.primary();
                queryBlock.setFirst(first);
            }
        }

        if (lexer.token == Token.DISTINCT) {
            queryBlock.setDistionOption(SQLSetQuantifier.DISTINCT);
            lexer.nextToken();
        } else if (lexer.token == Token.UNIQUE) {
            queryBlock.setDistionOption(SQLSetQuantifier.UNIQUE);
            lexer.nextToken();
        } else if (lexer.token == Token.ALL) {
            queryBlock.setDistionOption(SQLSetQuantifier.ALL);
            lexer.nextToken();
        }

        parseSelectList(queryBlock);

        parseFrom(queryBlock);

        parseWhere(queryBlock);

        parseGroupBy(queryBlock);

        parseFetchClause(queryBlock);

        return queryRest(queryBlock);
    }

    protected void withSubquery(SQLSelect select) {
        if (lexer.token == Token.WITH) {
            lexer.nextToken();

            SQLWithSubqueryClause withQueryClause = new SQLWithSubqueryClause();

            if (lexer.token == Token.RECURSIVE || lexer.identifierEquals(FnvHash.Constants.RECURSIVE)) {
                lexer.nextToken();
                withQueryClause.setRecursive(true);
            }

            for (;;) {
                SQLWithSubqueryClause.Entry entry = new SQLWithSubqueryClause.Entry();
                entry.setParent(withQueryClause);

                String alias = this.lexer.stringVal();
                lexer.nextToken();
                entry.setAlias(alias);

                if (lexer.token == Token.LPAREN) {
                    lexer.nextToken();
                    exprParser.names(entry.getColumns());
                    accept(Token.RPAREN);
                }

                accept(Token.AS);
                accept(Token.LPAREN);
                entry.setSubQuery(select());
                accept(Token.RPAREN);

                withQueryClause.addEntry(entry);

                if (lexer.token == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                }

                break;
            }

            select.setWithSubQuery(withQueryClause);
        }
    }

    public SQLWithSubqueryClause parseWith() {
        accept(Token.WITH);

        SQLWithSubqueryClause withQueryClause = new SQLWithSubqueryClause();

        if (lexer.token == Token.RECURSIVE || lexer.identifierEquals(FnvHash.Constants.RECURSIVE)) {
            lexer.nextToken();
            withQueryClause.setRecursive(true);
        }

        for (;;) {
            SQLWithSubqueryClause.Entry entry = new SQLWithSubqueryClause.Entry();
            entry.setParent(withQueryClause);

            String alias = this.lexer.stringVal();
            lexer.nextToken();
            entry.setAlias(alias);

            if (lexer.token == Token.LPAREN) {
                lexer.nextToken();
                exprParser.names(entry.getColumns());
                accept(Token.RPAREN);
            }

            accept(Token.AS);
            accept(Token.LPAREN);

            switch (lexer.token) {
                case SELECT:
                    entry.setSubQuery(select());
                    break;
                default:
                    break;
            }

            accept(Token.RPAREN);

            withQueryClause.addEntry(entry);

            if (lexer.token == Token.COMMA) {
                lexer.nextToken();
                continue;
            }

            break;
        }

        return withQueryClause;
    }

    protected void parseWhere(SQLSelectQueryBlock queryBlock) {
        if (lexer.token == Token.WHERE) {
            lexer.nextToken();

            List<String> beforeComments = null;
            if (lexer.hasComment() && lexer.isKeepComments()) {
                beforeComments = lexer.readAndResetComments();
            }
            SQLExpr where = expr();
            
            if (beforeComments != null) {
                where.addBeforeComment(beforeComments);
            }
            
            if (lexer.hasComment() && lexer.isKeepComments() //
                    && lexer.token != Token.INSERT // odps multi-insert
                    ) {
                where.addAfterComment(lexer.readAndResetComments());
            }
            
            queryBlock.setWhere(where);
        }
    }
    
    protected void parseGroupBy(SQLSelectQueryBlock queryBlock) {
        if (lexer.token == (Token.GROUP)) {
            lexer.nextTokenBy();
            accept(Token.BY);

            SQLSelectGroupByClause groupBy = new SQLSelectGroupByClause();
            if (lexer.identifierEquals(FnvHash.Constants.ROLLUP)) {
                lexer.nextToken();
                accept(Token.LPAREN);
                groupBy.setWithRollUp(true);
            }
            if (lexer.identifierEquals(FnvHash.Constants.CUBE)) {
                lexer.nextToken();
                accept(Token.LPAREN);
                groupBy.setWithCube(true);
            }

            for (;;) {
                SQLExpr item = parseGroupByItem();
                
                item.setParent(groupBy);
                groupBy.addItem(item);

                if (!(lexer.token == (Token.COMMA))) {
                    break;
                }

                lexer.nextToken();
            }
            if (groupBy.isWithRollUp() || groupBy.isWithCube()) {
                accept(Token.RPAREN);
            }

            if (lexer.token == (Token.HAVING)) {
                lexer.nextToken();

                SQLExpr having = this.exprParser.expr();
                groupBy.setHaving(having);
            }
            
            if (lexer.token == Token.WITH) {
                lexer.nextToken();
                
                if (lexer.identifierEquals(FnvHash.Constants.CUBE)) {
                    lexer.nextToken();
                    groupBy.setWithCube(true);
                } else {
                    acceptIdentifier("ROLLUP");
                    groupBy.setWithRollUp(true);
                }
            }
            
            queryBlock.setGroupBy(groupBy);
        } else if (lexer.token == (Token.HAVING)) {
            lexer.nextToken();

            SQLSelectGroupByClause groupBy = new SQLSelectGroupByClause();
            groupBy.setHaving(this.exprParser.expr());

            if (lexer.token == (Token.GROUP)) {
                lexer.nextToken();
                accept(Token.BY);

                for (;;) {
                    SQLExpr item = parseGroupByItem();
                    
                    item.setParent(groupBy);
                    groupBy.addItem(item);

                    if (!(lexer.token == (Token.COMMA))) {
                        break;
                    }

                    lexer.nextToken();
                }
            }
            
            if (lexer.token == Token.WITH) {
                lexer.nextToken();
                acceptIdentifier("ROLLUP");

                groupBy.setWithRollUp(true);
            }
            
            if(JdbcConstants.MYSQL.equals(getDbType())
                    && lexer.token == Token.DESC) {
                lexer.nextToken(); // skip
            }

            queryBlock.setGroupBy(groupBy);
        }
    }

    protected SQLExpr parseGroupByItem() {
        SQLExpr item = this.exprParser.expr();
        
        if(JdbcConstants.MYSQL.equals(getDbType())) {
            if (lexer.token == Token.DESC) {
                lexer.nextToken(); // skip
                item =new MySqlOrderingExpr(item, SQLOrderingSpecification.DESC);
            } else if (lexer.token == Token.ASC) {
                lexer.nextToken(); // skip
                item =new MySqlOrderingExpr(item, SQLOrderingSpecification.ASC);
            }
        }
        return item;
    }

    protected void parseSelectList(SQLSelectQueryBlock queryBlock) {
        final List<SQLSelectItem> selectList = queryBlock.getSelectList();
        for (;;) {
            final SQLSelectItem selectItem = this.exprParser.parseSelectItem();
            selectList.add(selectItem);
            selectItem.setParent(queryBlock);

            if (lexer.token != Token.COMMA) {
                break;
            }

            lexer.nextToken();
        }
    }

    public void parseFrom(SQLSelectQueryBlock queryBlock) {
        if (lexer.token != Token.FROM) {
            return;
        }
        
        lexer.nextToken();
        
        queryBlock.setFrom(
                parseTableSource());
    }

    public SQLTableSource parseTableSource() {
        if (lexer.token == Token.LPAREN) {
            lexer.nextToken();
            SQLTableSource tableSource;
            if (lexer.token == Token.SELECT || lexer.token == Token.WITH
            		|| lexer.token == Token.SEL) {
                SQLSelect select = select();
                accept(Token.RPAREN);
                SQLSelectQuery query = queryRest(select.getQuery());
                if (query instanceof SQLUnionQuery) {
                    tableSource = new SQLUnionQueryTableSource((SQLUnionQuery) query);
                } else {
                    tableSource = new SQLSubqueryTableSource(select);
                }
            } else if (lexer.token == Token.LPAREN) {
                tableSource = parseTableSource();
                accept(Token.RPAREN);
            } else {
                tableSource = parseTableSource();
                accept(Token.RPAREN);
            }

            return parseTableSourceRest(tableSource);
        }

        if (lexer.token == Token.SELECT) {
            throw new ParserException("TODO " + lexer.info());
        }

        SQLExprTableSource tableReference = new SQLExprTableSource();

        parseTableSourceQueryTableExpr(tableReference);

        SQLTableSource tableSrc = parseTableSourceRest(tableReference);
        
        if (lexer.hasComment() && lexer.isKeepComments()) {
            tableSrc.addAfterComment(lexer.readAndResetComments());
        }
        
        return tableSrc;
    }

    protected void parseTableSourceQueryTableExpr(SQLExprTableSource tableReference) {
        if (lexer.token == Token.LITERAL_ALIAS || lexer.token == Token.IDENTIFIED
            || lexer.token == Token.LITERAL_CHARS) {
            tableReference.setExpr(this.exprParser.name());
            return;
        }

        tableReference.setExpr(expr());
    }

    protected SQLTableSource primaryTableSourceRest(SQLTableSource tableSource) {
        return tableSource;
    }

    protected SQLTableSource parseTableSourceRest(SQLTableSource tableSource) {
        if ((tableSource.getAlias() == null) || (tableSource.getAlias().length() == 0)) {
            if (lexer.token != Token.LEFT && lexer.token != Token.RIGHT && lexer.token != Token.FULL
                    && (!lexer.identifierEquals(FnvHash.Constants.STRAIGHT_JOIN))
                    && (!lexer.identifierEquals(FnvHash.Constants.CROSS))
                    && lexer.token != Token.OUTER) {
                String alias = tableAlias();
                if (alias != null) {
                    tableSource.setAlias(alias);
                    return parseTableSourceRest(tableSource);
                }
            }
        }

        SQLJoinTableSource.JoinType joinType = null;

        boolean natural = lexer.identifierEquals(FnvHash.Constants.NATURAL) && JdbcConstants.MYSQL.equals(dbType);
        if (natural) {
            lexer.nextToken();
        }

        if (lexer.token == Token.LEFT) {
            lexer.nextToken();

            if (lexer.identifierEquals(FnvHash.Constants.SEMI)) {
                lexer.nextToken();
                joinType = SQLJoinTableSource.JoinType.LEFT_SEMI_JOIN;
            } else if (lexer.identifierEquals(FnvHash.Constants.ANTI)) {
                lexer.nextToken();
                joinType = SQLJoinTableSource.JoinType.LEFT_ANTI_JOIN;
            } else if (lexer.token == Token.OUTER) {
                lexer.nextToken();
                joinType = SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN;
            } else {
                joinType = SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN;
            }

            accept(Token.JOIN);

        } else if (lexer.token == Token.RIGHT) {
            lexer.nextToken();
            if (lexer.token == Token.OUTER) {
                lexer.nextToken();
            }
            accept(Token.JOIN);
            joinType = SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN;
        } else if (lexer.token == Token.FULL) {
            lexer.nextToken();
            if (lexer.token == Token.OUTER) {
                lexer.nextToken();
            }
            accept(Token.JOIN);
            joinType = SQLJoinTableSource.JoinType.FULL_OUTER_JOIN;
        } else if (lexer.token == Token.INNER) {
            lexer.nextToken();
            accept(Token.JOIN);
            joinType = SQLJoinTableSource.JoinType.INNER_JOIN;
        } else if (lexer.token == Token.JOIN) {
            lexer.nextToken();
            joinType = SQLJoinTableSource.JoinType.JOIN;
        } else if (lexer.token == Token.COMMA) {
            lexer.nextToken();
            joinType = SQLJoinTableSource.JoinType.COMMA;
        } else if (lexer.identifierEquals(FnvHash.Constants.STRAIGHT_JOIN)) {
            lexer.nextToken();
            joinType = SQLJoinTableSource.JoinType.STRAIGHT_JOIN;
        } else if (lexer.identifierEquals(FnvHash.Constants.CROSS)) {
            lexer.nextToken();
            if (lexer.token == Token.JOIN) {
                lexer.nextToken();
                joinType = SQLJoinTableSource.JoinType.CROSS_JOIN;
            } else if (lexer.identifierEquals(FnvHash.Constants.APPLY)) {
                lexer.nextToken();
                joinType = SQLJoinTableSource.JoinType.CROSS_APPLY;
            }
        } else if (lexer.token == Token.OUTER) {
            lexer.nextToken();
            if (lexer.identifierEquals(FnvHash.Constants.APPLY)) {
                lexer.nextToken();
                joinType = SQLJoinTableSource.JoinType.OUTER_APPLY;
            }
        }

        if (joinType != null) {
            SQLJoinTableSource join = new SQLJoinTableSource();
            join.setLeft(tableSource);
            join.setJoinType(joinType);


            SQLTableSource rightTableSource;
            if (lexer.token == Token.LPAREN) {
                lexer.nextToken();
                SQLSelect select = this.select();
                rightTableSource = new SQLSubqueryTableSource(select);
                accept(Token.RPAREN);
            } else {
                SQLExpr expr = this.expr();
                rightTableSource = new SQLExprTableSource(expr);
            }

            if (lexer.token == Token.USING
                ||lexer.identifierEquals(FnvHash.Constants.USING))
            {
                Lexer.SavePoint savePoint = lexer.mark();
                lexer.nextToken();

                if (lexer.token == Token.LPAREN
                        || lexer.token == Token.IDENTIFIER) {
                    lexer.reset(savePoint);
                    join.setRight(rightTableSource);
                    return join;
                } else {
                    join.setAlias(this.tableAlias());
                }
            } else {
                rightTableSource.setAlias(this.tableAlias());

                primaryTableSourceRest(rightTableSource);
            }

            if (lexer.token == Token.WITH) {
                lexer.nextToken();
                accept(Token.LPAREN);

                for (;;) {
                    SQLExpr hintExpr = this.expr();
                    SQLExprHint hint = new SQLExprHint(hintExpr);
                    hint.setParent(tableSource);
                    rightTableSource.getHints().add(hint);
                    if (lexer.token == Token.COMMA) {
                        lexer.nextToken();
                        continue;
                    } else {
                        break;
                    }
                }

                accept(Token.RPAREN);
            }

            join.setRight(rightTableSource);

            if (!natural) {
                if (tableSource.aliasHashCode64() == FnvHash.Constants.NATURAL && JdbcConstants.MYSQL.equals(dbType)) {
                    tableSource.setAlias(null);
                    natural = true;
                }
            }
            join.setNatural(natural);

            if (lexer.token == Token.ON) {
                lexer.nextToken();
                join.setCondition(expr());
            } else if (lexer.token == Token.USING
                    || lexer.identifierEquals(FnvHash.Constants.USING)) {
                Lexer.SavePoint savePoint = lexer.mark();
                lexer.nextToken();
                if (lexer.token == Token.LPAREN) {
                    lexer.nextToken();
                    this.exprParser.exprList(join.getUsing(), join);
                    accept(Token.RPAREN);
                } else {
                    lexer.reset(savePoint);
                }
            }

            return parseTableSourceRest(join);
        }

        return tableSource;
    }

    public SQLExpr expr() {
        return this.exprParser.expr();
    }

    public SQLOrderBy parseOrderBy() {
        return this.exprParser.parseOrderBy();
    }

    public void acceptKeyword(String ident) {
        if (lexer.token == Token.IDENTIFIER && ident.equalsIgnoreCase(lexer.stringVal())) {
            lexer.nextToken();
        } else {
            setErrorEndPos(lexer.pos());
            throw new ParserException("syntax error, expect " + ident + ", actual " + lexer.token + ", " + lexer.info());
        }
    }

    public void parseFetchClause(SQLSelectQueryBlock queryBlock) {
        if (lexer.token == Token.LIMIT) {
            SQLLimit limit = this.exprParser.parseLimit();
            queryBlock.setLimit(limit);
            return;
        }

        if (lexer.identifierEquals(FnvHash.Constants.OFFSET) || lexer.token == Token.OFFSET) {
            lexer.nextToken();
            SQLExpr offset = this.exprParser.primary();
            queryBlock.setOffset(offset);
            if (lexer.identifierEquals(FnvHash.Constants.ROW) || lexer.identifierEquals(FnvHash.Constants.ROWS)) {
                lexer.nextToken();
            }
        }

        if (lexer.token == Token.FETCH) {
            lexer.nextToken();
            if (lexer.token == Token.FIRST
                    || lexer.identifierEquals(FnvHash.Constants.NEXT)) {
                lexer.nextToken();
            } else {
                acceptIdentifier("FIRST");
            }
            SQLExpr first = this.exprParser.primary();
            queryBlock.setFirst(first);
            if (lexer.identifierEquals(FnvHash.Constants.ROW) || lexer.identifierEquals(FnvHash.Constants.ROWS)) {
                lexer.nextToken();
            }

            if (lexer.token == Token.ONLY) {
                lexer.nextToken();
            } else {
                acceptIdentifier("ONLY");
            }
        }
    }

    protected void parseHierachical(SQLSelectQueryBlock queryBlock) {
        if (lexer.token == Token.CONNECT || lexer.identifierEquals(FnvHash.Constants.CONNECT)) {
            lexer.nextToken();
            accept(Token.BY);

            if (lexer.token == Token.PRIOR || lexer.identifierEquals(FnvHash.Constants.PRIOR)) {
                lexer.nextToken();
                queryBlock.setPrior(true);
            }

            if (lexer.identifierEquals(FnvHash.Constants.NOCYCLE)) {
                queryBlock.setNoCycle(true);
                lexer.nextToken();

                if (lexer.token == Token.PRIOR) {
                    lexer.nextToken();
                    queryBlock.setPrior(true);
                }
            }
            queryBlock.setConnectBy(this.exprParser.expr());
        }

        if (lexer.token == Token.START || lexer.identifierEquals(FnvHash.Constants.START)) {
            lexer.nextToken();
            accept(Token.WITH);

            queryBlock.setStartWith(this.exprParser.expr());
        }

        if (lexer.token == Token.CONNECT || lexer.identifierEquals(FnvHash.Constants.CONNECT)) {
            lexer.nextToken();
            accept(Token.BY);

            if (lexer.token == Token.PRIOR || lexer.identifierEquals(FnvHash.Constants.PRIOR)) {
                lexer.nextToken();
                queryBlock.setPrior(true);
            }

            if (lexer.identifierEquals(FnvHash.Constants.NOCYCLE)) {
                queryBlock.setNoCycle(true);
                lexer.nextToken();

                if (lexer.token == Token.PRIOR || lexer.identifierEquals(FnvHash.Constants.PRIOR)) {
                    lexer.nextToken();
                    queryBlock.setPrior(true);
                }
            }
            queryBlock.setConnectBy(this.exprParser.expr());
        }
    }
}
