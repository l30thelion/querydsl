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
package com.mysema.query.jpa.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.persistence.EntityManager;
import javax.persistence.FlushModeType;
import javax.persistence.LockModeType;
import javax.persistence.Query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.mysema.commons.lang.CloseableIterator;
import com.mysema.query.*;
import com.mysema.query.jpa.AbstractSQLQuery;
import com.mysema.query.jpa.NativeSQLSerializer;
import com.mysema.query.jpa.QueryHandler;
import com.mysema.query.jpa.impl.JPAProvider;
import com.mysema.query.jpa.impl.JPAUtil;
import com.mysema.query.sql.Configuration;
import com.mysema.query.sql.SQLSerializer;
import com.mysema.query.types.Expression;
import com.mysema.query.types.FactoryExpression;
import com.mysema.query.types.FactoryExpressionUtils;

/**
 * AbstractJPASQLQuery is the base class for JPA Native SQL queries
 *
 * @author tiwe
 *
 * @param <Q>
 */
public abstract class AbstractJPASQLQuery<Q extends AbstractJPASQLQuery<Q>> extends AbstractSQLQuery<Q> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractJPASQLQuery.class);

    private final EntityManager entityManager;

    protected final Multimap<String,Object> hints = HashMultimap.create();

    protected final QueryHandler queryHandler;

    @Nullable
    protected LockModeType lockMode;

    @Nullable
    protected FlushModeType flushMode;

    @Nullable

    protected FactoryExpression<?> projection;

    public AbstractJPASQLQuery(EntityManager em, Configuration configuration) {
        this(em, configuration, new DefaultQueryMetadata().noValidate());
    }

    public AbstractJPASQLQuery(EntityManager em, Configuration configuration, QueryHandler queryHandler) {
        this(em, configuration, queryHandler, new DefaultQueryMetadata().noValidate());
    }

    public AbstractJPASQLQuery(EntityManager em, Configuration configuration, QueryMetadata metadata) {
        this(em, configuration, JPAProvider.getTemplates(em).getQueryHandler(), metadata);
    }

    public AbstractJPASQLQuery(EntityManager em, Configuration configuration, QueryHandler queryHandler, QueryMetadata metadata) {
        super(metadata, configuration);
        this.entityManager = em;
        this.queryHandler = queryHandler;
    }

    public Query createQuery(Expression<?>... args) {
        queryMixin.getMetadata().setValidate(false);
        queryMixin.addProjection(args);
        return createQuery(false);
    }

    private Query createQuery(boolean forCount) {
        NativeSQLSerializer serializer = (NativeSQLSerializer) serialize(forCount);
        String queryString = serializer.toString();
        logQuery(queryString, serializer.getLabelToConstant());
        List<? extends Expression<?>> projection = queryMixin.getMetadata().getProjection();
        Query query;

        Expression<?> proj = projection.get(0);
        if (!FactoryExpression.class.isAssignableFrom(proj.getClass()) && isEntityExpression(proj)) {
            if (projection.size() == 1) {
                if (queryHandler.createNativeQueryTyped()) {
                    query = entityManager.createNativeQuery(queryString, proj.getType());
                } else {
                    query = entityManager.createNativeQuery(queryString);
                }
            } else {
                throw new IllegalArgumentException("Only single element entity projections are supported");
            }

        } else {
            query = entityManager.createNativeQuery(queryString);
        }
        if (!forCount) {
            ListMultimap<Expression<?>, String> aliases = serializer.getAliases();
            Set<String> used = Sets.newHashSet();
            if (proj instanceof FactoryExpression) {
                for (Expression<?> expr : ((FactoryExpression<?>)proj).getArgs()) {
                    if (isEntityExpression(expr)) {
                        queryHandler.addEntity(query, extractEntityExpression(expr).toString(), expr.getType());
                    } else if (aliases.containsKey(expr)) {
                        for (String scalar : aliases.get(expr)) {
                            if (!used.contains(scalar)) {
                                queryHandler.addScalar(query, scalar, expr.getType());
                                used.add(scalar);
                                break;
                            }

                        }
                    }
                }
            } else if (isEntityExpression(proj)) {
                queryHandler.addEntity(query, extractEntityExpression(proj).toString(), proj.getType());
            } else if (aliases.containsKey(proj)) {
                for (String scalar : aliases.get(proj)) {
                    if (!used.contains(scalar)) {
                        queryHandler.addScalar(query, scalar, proj.getType());
                        used.add(scalar);
                        break;
                    }
                }
            }
        }

        if (lockMode != null) {
            query.setLockMode(lockMode);
        }
        if (flushMode != null) {
            query.setFlushMode(flushMode);
        }

        for (Map.Entry<String, Object> entry : hints.entries()) {
            query.setHint(entry.getKey(), entry.getValue());
        }


        // set constants
        JPAUtil.setConstants(query, serializer.getLabelToConstant(), queryMixin.getMetadata().getParams());
        this.projection = null; // necessary when query is reused

        FactoryExpression<?> wrapped = projection.size() > 1 ? FactoryExpressionUtils.wrap(projection) : null;
        if ((projection.size() == 1 && projection.get(0) instanceof FactoryExpression) || wrapped != null) {
            Expression<?> expr = wrapped != null ? wrapped : projection.get(0);

            if (!queryHandler.transform(query, (FactoryExpression<?>)expr)) {
                this.projection = (FactoryExpression<?>)projection.get(0);
                if (wrapped != null) {
                    this.projection = wrapped;
                    getMetadata().clearProjection();
                    getMetadata().addProjection(wrapped);
                }
            }
        }

        return query;
    }

    @Override
    protected SQLSerializer createSerializer() {
        return new NativeSQLSerializer(configuration, queryHandler.wrapEntityProjections());
    }

    /**
     * Transforms results using FactoryExpression if ResultTransformer can't be used
     *
     * @param query
     * @return
     */
    private List<?> getResultList(Query query) {
        // TODO : use lazy list here?
        if (projection != null) {
            List<?> results = query.getResultList();
            List<Object> rv = new ArrayList<Object>(results.size());
            for (Object o : results) {
                if (o != null) {
                    Object[] arr;
                    if (!o.getClass().isArray()) {
                        arr = new Object[]{o};
                    } else {
                        arr = (Object[])o;
                    }
                    if (projection.getArgs().size() < arr.length) {
                        Object[] shortened = new Object[projection.getArgs().size()];
                        System.arraycopy(arr, 0, shortened, 0, shortened.length);
                        arr = shortened;
                    }
                    rv.add(projection.newInstance(arr));
                } else {
                    rv.add(null);
                }
            }
            return rv;
        } else {
            return query.getResultList();
        }
    }

    /**
     * Transforms results using FactoryExpression if ResultTransformer can't be used
     *
     * @param query
     * @return
     */
    @Nullable
    private Object getSingleResult(Query query) {
        if (projection != null) {
            Object result = query.getSingleResult();
            if (result != null) {
                if (!result.getClass().isArray()) {
                    result = new Object[]{result};
                }
                return projection.newInstance((Object[])result);
            } else {
                return null;
            }
        } else {
            return query.getSingleResult();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <RT> List<RT> list(Expression<RT> projection) {
        try {
            Query query = createQuery(projection);
            return (List<RT>) getResultList(query);
        } finally {
            reset();
        }
    }

    @Override
    public <RT> CloseableIterator<RT> iterate(Expression<RT> expr) {
        try {
            Query query = createQuery(expr);
            return queryHandler.<RT>iterate(query, null);
        } finally {
            reset();
        }
    }

    @Override
    public <RT> SearchResults<RT> listResults(Expression<RT> projection) {
        // TODO : handle entity projections as well
        try {
            queryMixin.addProjection(projection);
            Query query = createQuery(true);
            long total = ((Number)query.getSingleResult()).longValue();
            if (total > 0) {
                QueryModifiers modifiers = queryMixin.getMetadata().getModifiers();
                query = createQuery(false);
                @SuppressWarnings("unchecked")
                List<RT> list = (List<RT>) getResultList(query);
                return new SearchResults<RT>(list, modifiers, total);
            } else {
                return SearchResults.emptyResults();
            }
        } finally {
            reset();
        }

    }


    protected void logQuery(String queryString, Map<String, Object> parameters) {
        if (logger.isDebugEnabled()) {
            String normalizedQuery = queryString.replace('\n', ' ');
            MDC.put(MDC_QUERY, normalizedQuery);
            MDC.put(MDC_PARAMETERS, String.valueOf(parameters));
            logger.debug(normalizedQuery);
        }
    }

    protected void cleanupMDC() {
        MDC.remove(MDC_QUERY);
        MDC.remove(MDC_PARAMETERS);
    }

    protected void reset() {
        getMetadata().clearProjection();
        cleanupMDC();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <RT> RT uniqueResult(Expression<RT> expr) {
        Query query = createQuery(expr);
        return (RT)uniqueResult(query);
    }

    @Nullable
    private Object uniqueResult(Query query) {
        try{
            return getSingleResult(query);
        } catch(javax.persistence.NoResultException e) {
            logger.trace(e.getMessage(),e);
            return null;
        } catch(javax.persistence.NonUniqueResultException e) {
            throw new NonUniqueResultException();
        } finally {
            reset();
        }
    }

    @SuppressWarnings("unchecked")
    public Q setLockMode(LockModeType lockMode) {
        this.lockMode = lockMode;
        return (Q)this;
    }

    @SuppressWarnings("unchecked")
    public Q setFlushMode(FlushModeType flushMode) {
        this.flushMode = flushMode;
        return (Q)this;
    }

    @SuppressWarnings("unchecked")
    public Q setHint(String name, Object value) {
        hints.put(name, value);
        return (Q)this;
    }

    @Override
    protected void clone(Q query) {
        super.clone(query);
        flushMode = query.flushMode;
        hints.putAll(query.hints);
        lockMode = query.lockMode;
        projection = query.projection;
    }
    
    public abstract Q clone(EntityManager entityManager);
    
    @Override
    public Q clone() {
        return this.clone(this.entityManager);
    }

}
