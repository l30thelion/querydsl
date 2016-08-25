/*
 * Copyright 2015, The Querydsl Team (http://www.querydsl.com/team)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mysema.query.jdo;

import javax.annotation.Nullable;
import java.util.*;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Primitives;
import com.mysema.query.JoinExpression;
import com.mysema.query.QueryMetadata;
import com.mysema.query.support.SerializerBase;
import com.mysema.query.types.*;
import com.mysema.query.types.expr.Param;

/**
 * JDOQLSerializer serializes Querydsl queries and expressions into JDOQL strings
 *
 * @author tiwe
 *
 */
public final class JDOQLSerializer extends SerializerBase<JDOQLSerializer> {

    private static final String COMMA = ", ";

    private static final String FROM = "\nFROM ";

    private static final String GROUP_BY = "\nGROUP BY ";

    private static final String HAVING = "\nHAVING ";

    private static final String ORDER_BY = "\nORDER BY ";

    private static final String PARAMETERS = "\nPARAMETERS ";

    private static final String RANGE = "\nRANGE ";

    private static final String SELECT = "SELECT ";

    private static final String SELECT_COUNT = "SELECT count(";

    private static final String SELECT_COUNT_THIS = "SELECT count(this)\n";

    private static final String SELECT_DISTINCT = "SELECT DISTINCT ";

    private static final String SELECT_UNIQUE = "SELECT UNIQUE ";

    private static final String THIS = "this";

    private static final String VARIABLES = "\nVARIABLES ";

    private static final String WHERE = "\nWHERE ";
    
    private static Comparator<Map.Entry<String,Object>> comparator = new Comparator<Map.Entry<String,Object>>() {
        @Override
        public int compare(Entry<String, Object> o1, Entry<String, Object> o2) {
            return o1.getKey().compareTo(o2.getKey());
        }
    };

    private final Expression<?> candidatePath;

    private final List<Object> constants = new ArrayList<Object>();

    private final Stack<Map<String,Object>> labelToConstant = new Stack<Map<String,Object>>();
    
    public JDOQLSerializer(JDOQLTemplates templates, Expression<?> candidate) {
        super(templates);
        this.candidatePath = candidate;
        this.labelToConstant.push(new HashMap<String,Object>());
    }

    public Expression<?> getCandidatePath() {
        return candidatePath;
    }

    public List<Object> getConstants() {
        return constants;
    }
    
    @Override
    public Map<String,Object> getLabelToConstant() {
        return labelToConstant.peek();
    }

    public void serialize(QueryMetadata metadata, boolean forCountRow, boolean subQuery) {
        final List<? extends Expression<?>> select = metadata.getProjection();
        final List<JoinExpression> joins = metadata.getJoins();
        final Expression<?> source = joins.get(0).getTarget();
        final Predicate where = metadata.getWhere();
        final List<? extends Expression<?>> groupBy = metadata.getGroupBy();
        final Predicate having = metadata.getHaving();
        final List<OrderSpecifier<?>> orderBy = metadata.getOrderBy();

        labelToConstant.push(new HashMap<String,Object>());
        
        // select
        boolean skippedSelect = false;
        if (forCountRow) {
            if (joins.size() == 1 && !subQuery) {
                append(SELECT_COUNT_THIS);
            } else {
                append(SELECT_COUNT);
                handle(source);
                append(")");
            }

        } else if (!select.isEmpty()) {
            if (metadata.isDistinct()) {
                append(SELECT_DISTINCT);
            } else if (metadata.isUnique() && !subQuery) {
                append(SELECT_UNIQUE);
            } else {
                append(SELECT);
            }
            if (select.size() >1 || !select.get(0).equals(source) || metadata.isDistinct()) {
                handle(COMMA, select);    
            } else {
                skippedSelect = true;
            }
        }

        // from
        append(skippedSelect ? FROM.substring(1) : FROM);
        if (source instanceof Operation && subQuery) {
            handle(source);
        } else {
            append(source.getType().getName());
            if (!source.equals(candidatePath)) {
                append(" ").handle(source);
            }
        }

        // where
        if (where != null) {
            append(WHERE).handle(where);
        }

        // variables
        if (joins.size() > 1) {
            serializeVariables(joins);
        }
        
        int position = getLength();
        
        // group by
        if (!groupBy.isEmpty()) {
            append(GROUP_BY).handle(COMMA, groupBy);
        }

        // having
        if (having != null) {
            append(HAVING).handle(having);
        }

        // order by
        if (!orderBy.isEmpty() && !forCountRow) {
            append(ORDER_BY);
            boolean first = true;
            for (final OrderSpecifier<?> os : orderBy) {
                if (!first) {
                    append(COMMA);
                }
                handle(os.getTarget());
                append(" " + os.getOrder());
                first = false;
            }
        }

        // range
        if (!forCountRow && metadata.getModifiers().isRestricting()) {
            Long limit = metadata.getModifiers().getLimit();
            Long offset = metadata.getModifiers().getOffset();
            serializeModifiers(limit, offset);
        }
        
        // parameters        
        if (!getLabelToConstant().isEmpty()) {
            insert(position, serializeParameters(metadata.getParams()));
        }
        
        labelToConstant.pop();
        
    }

    private void serializeModifiers(@Nullable Long limit, @Nullable Long offset) {
        append(RANGE);
        if (offset != null) {
            append(String.valueOf(offset));
            if (limit != null) {
                append(COMMA);
                append(String.valueOf(offset + limit));
            }
        } else {
            append("0, ").append(String.valueOf(limit));
        }
    }

    private String serializeParameters(Map<ParamExpression<?>, Object> params) {
        final StringBuilder b = new StringBuilder();
        b.append(PARAMETERS);
        boolean first = true;
        final List<Map.Entry<String, Object>> entries = new ArrayList<Map.Entry<String, Object>>(getLabelToConstant().entrySet());
        Collections.sort(entries, comparator);
        for (Map.Entry<String, Object> entry : entries) {
            if (!first) {
                b.append(COMMA);
            }
            if (Param.class.isInstance(entry.getValue())) {
                Object constant = params.get(entry.getValue());
                if (constant == null) {
                    throw new ParamNotSetException((Param<?>) entry.getValue());
                }
                constants.add(constant);
                b.append(((Param<?>)entry.getValue()).getType().getName());
            } else {
                constants.add(entry.getValue());
                b.append(entry.getValue().getClass().getName());
            }
            b.append(" ").append(entry.getKey());
            first = false;
        }
        return b.toString();
    }

    private void serializeVariables(List<JoinExpression> joins) {
        append(VARIABLES);
        for (int i = 1; i < joins.size(); i++) {
            final JoinExpression je = joins.get(i);
            if (i > 1) {
                append("; ");
            }

            // type specifier
            if (je.getTarget() instanceof EntityPath) {
                final EntityPath<?> pe = (EntityPath<?>) je.getTarget();
                if (pe.getMetadata().getParent() == null) {
                    append(pe.getType().getName()).append(" ");
                }
            }
            handle(je.getTarget());
        }
    }

    @Override
    public Void visit(Path<?> path, Void context) {
        if (path.equals(candidatePath)) {
            append(THIS);
        } else {
            super.visit(path, context);
        }
        return null;
    }

    @Override
    public Void visit(SubQueryExpression<?> query, Void context) {
        append("(");
        serialize(query.getMetadata(), false, true);
        append(")");
        return null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    protected void visitOperation(Class<?> type, Operator<?> operator, List<? extends Expression<?>> args) {
        if (operator == Ops.INSTANCE_OF) {
            handle(args.get(0)).append(" instanceof ");
            append(((Constant<Class<?>>) args.get(1)).getConstant().getName());

        } else if (operator == Ops.LIKE || operator == Ops.LIKE_ESCAPE || operator == Ops.LIKE_IC || operator == Ops.LIKE_ESCAPE_IC) {
            @SuppressWarnings("unchecked") //This is the expected type for like
            Expression<String> string = (Expression<String>) args.get(0);
            @SuppressWarnings("unchecked") //This is the expected type for like
            Expression<String> regex = ExpressionUtils.likeToRegex((Expression<String>) args.get(1), false);
            if (operator == Ops.LIKE_IC || operator == Ops.LIKE_ESCAPE_IC) {
                string = ExpressionUtils.toLower(string);
                regex = ExpressionUtils.toLower(regex);
            }
            super.visitOperation(type, Ops.MATCHES,
                ImmutableList.of(string, regex));

        // exists
        } else if (operator == Ops.EXISTS && args.get(0) instanceof SubQueryExpression) {
            final SubQueryExpression subQuery = (SubQueryExpression) args.get(0);
            append("(");
            serialize(subQuery.getMetadata(), true, true);
            append(") > 0");

        // not exists    
        } else if (operator == Ops.NOT && args.get(0) instanceof Operation 
                && ((Operation)args.get(0)).getOperator().equals(Ops.EXISTS)) {    
            final SubQueryExpression subQuery = (SubQueryExpression) ((Operation)args.get(0)).getArg(0);
            append("(");
            serialize(subQuery.getMetadata(), true, true);
            append(") == 0");
                
        } else if (operator == Ops.NUMCAST) {
            Class<?> clazz = ((Constant<Class<?>>)args.get(1)).getConstant();
            if (Number.class.isAssignableFrom(clazz) && Primitives.isWrapperType(clazz)) {
                clazz = Primitives.unwrap(clazz);
            }
            append("("+clazz.getSimpleName()+")").handle(args.get(0));

        } else {
            super.visitOperation(type, operator, args);
        }
    }
    

}
