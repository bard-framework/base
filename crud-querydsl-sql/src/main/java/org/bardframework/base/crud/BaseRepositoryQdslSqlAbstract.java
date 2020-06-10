package org.bardframework.base.crud;

import com.querydsl.core.dml.StoreClause;
import com.querydsl.core.types.QBean;
import com.querydsl.core.types.dsl.ComparableExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.sql.RelationalPathBase;
import com.querydsl.sql.SQLQuery;
import com.querydsl.sql.SQLQueryFactory;
import com.querydsl.sql.dml.SQLInsertClause;
import com.querydsl.sql.dml.SQLUpdateClause;
import io.github.jhipster.service.filter.Filter;
import io.github.jhipster.service.filter.RangeFilter;
import io.github.jhipster.service.filter.StringFilter;
import org.bardframework.base.filter.IdFilter;
import org.bardframework.base.util.PageableExecutionUtils;
import org.bardframework.commons.utils.AssertionUtils;
import org.bardframework.commons.utils.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.ParameterizedType;
import java.util.Collections;
import java.util.List;

/**
 * Created by vahid on 1/17/17.
 */
public abstract class BaseRepositoryQdslSqlAbstract<M extends BaseModelAbstract<I>, C extends BaseCriteriaAbstract<I>, I extends Comparable<? super I>, U> implements BaseRepository<M, C, I, U> {

    private static final int DEFAULT_SIZE = 20;

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());
    protected final Class<M> modelClazz;
    protected final Class<C> criteriaClazz;

    @Autowired
    private SQLQueryFactory queryFactory;

    public BaseRepositoryQdslSqlAbstract() {
        ParameterizedType parameterizedType = null;
        Class<?> targetClazz = this.getClass();
        while (!(null != parameterizedType && parameterizedType.getActualTypeArguments().length >= 2) && null != targetClazz) {
            parameterizedType = targetClazz.getGenericSuperclass() instanceof ParameterizedType ? (ParameterizedType) targetClazz.getGenericSuperclass() : null;
            targetClazz = targetClazz.getSuperclass();
        }
        try {
            this.modelClazz = (Class<M>) ((ParameterizedType) this.getClass().getGenericSuperclass()).getActualTypeArguments()[0];
            this.criteriaClazz = (Class<C>) parameterizedType.getActualTypeArguments()[1];
        } catch (Exception e) {
            this.LOGGER.debug("can't determine class from generic type!", e);
            throw new IllegalArgumentException("can't determine class from generic type!", e);
        }
    }

    protected abstract <T> SQLQuery<T> setCriteria(C criteria, SQLQuery<T> query, U user);

    protected abstract RelationalPathBase<?> getEntity();

    protected abstract QBean<M> getQBean();

    protected abstract <T extends StoreClause<T>> T toClause(T clause, M model, U user);

    protected abstract M setIdentifier(M model, U user);

    private static boolean isUnpaged(Pageable pageable) {
        return pageable.isUnpaged();
    }

    public M getEmptyModel() {
        try {
            return modelClazz.newInstance();
        } catch (IllegalAccessException | InstantiationException e) {
            this.LOGGER.error("can't instantiate model class using empty constructor {}", this.modelClazz, e);
            throw new IllegalArgumentException("can't instantiate model class using empty constructor" + this.modelClazz, e);
        }
    }

    public C getEmptyCriteria() {
        C criteria;
        try {
            criteria = criteriaClazz.newInstance();
        } catch (IllegalAccessException | InstantiationException e) {
            this.LOGGER.error("can't instantiate criteria class using empty constructor {}", this.criteriaClazz, e);
            throw new IllegalArgumentException("can't instantiate criteria class using empty constructor" + this.criteriaClazz, e);
        }
        return criteria;
    }

    protected <T extends StoreClause<T>> T fillClause(T clause, M model, U user) {
        clause = this.toClause(clause, model, user);
        for (Class clazz : this.getClass().getInterfaces()) {
            if (WriteExtendedRepositoryQdslSql.class.isAssignableFrom(clazz)) {
                ((WriteExtendedRepositoryQdslSql) this).process(clause, model, user);
            }
        }
        return clause;
    }

    @Transactional
    @Override
    public M save(M model, U user) {
        return this.save(Collections.singletonList(model), user).get(0);
    }

    /**
     * @param models
     * @param user
     * @return not changed models if models is null or empty, saved models otherwise.
     */
    @Transactional
    @Override
    public List<M> save(List<M> models, U user) {
        if (CollectionUtils.isEmpty(models)) {
            return models;
        }
        SQLInsertClause insertClause = this.getQueryFactory().insert(this.getEntity());
        models.forEach(model -> {
                    this.setIdentifier(model, user);
                    AssertionUtils.notNull(model.getId(), "model identifier is not provided in 'setIdentifier' method");
                    insertClause.set(getIdentifierPath(), model.getId());
                    this.fillClause(insertClause, model, user);
                    insertClause.addBatch();
                }
        );
        long affectedRowsCount = insertClause.execute();
        if (models.size() != affectedRowsCount) {
            LOGGER.warn("expect insert '{}' row, but '{}' row(s) inserted.", models.size(), affectedRowsCount);
//            throw new IllegalStateException("expect affect '" + models.size() + "' row, but " + affectedRowsCount + " row(s) inserted.");
        }
        return models;
    }

    @Transactional
    @Override
    public M update(M model, U user) {
        SQLUpdateClause updateClause = this.getQueryFactory().update(getEntity()).where(this.getIdentifierPath().eq(model.getId()));
        updateClause = this.fillClause(updateClause, model, user);
        long affectedRowsCount = updateClause.execute();
        if (1 != affectedRowsCount) {
            throw new IllegalStateException("expect affect one row, but " + affectedRowsCount + " row(s) updated.");
        }
        return model;
    }

    public abstract <T extends ComparableExpression<I>> T getIdentifierPath();

    @Transactional(readOnly = true)
    @Override
    public M get(I identifier, U user) {
        AssertionUtils.notNull(identifier, "Given Identifier cannot be null.");
        C criteria = this.getEmptyCriteria();
        criteria.setId((IdFilter<I>) new IdFilter<I>().setEquals(identifier));
        return this.getOne(criteria, user);
    }

    @Transactional(readOnly = true)
    @Override
    public List<M> get(List<I> ids, U user) {
        AssertionUtils.notNull(ids, "Given Identifiers cannot be null.");
        C criteria = this.getEmptyCriteria();
        criteria.setId((IdFilter<I>) new IdFilter<I>().setIn(ids));
        return this.get(criteria, user);
    }

    public SQLQuery<?> prepareQuery(C criteria, U user) {
        SQLQuery<?> query = this.getQueryFactory().query();
        query.from(this.getEntity());
        query = this.setJoins(query, user);
        query = this.setCriteria(criteria, query, user);

        if (null != criteria.getId()) {
            this.buildQuery(query, criteria.getId(), (ComparableExpression<I>) this.getIdentifierPath());
        }

        for (Class clazz : this.getClass().getInterfaces()) {
            if (ReadExtendedRepositoryQdslSql.class.isAssignableFrom(clazz)) {
                ((ReadExtendedRepositoryQdslSql) this).process(criteria, query, user);
            }
        }
        this.setOrders(query, criteria, user);
        return query;
    }

    @Transactional(readOnly = true)
    @Override
    public Page<M> get(C criteria, Pageable pageable, U user) {
        AssertionUtils.notNull(criteria, "null criteria not acceptable");
        SQLQuery<?> query = this.prepareQuery(criteria, user);
        long count = query.fetchCount();
        if (0 > count) {
            return Page.empty();
        }
        query = this.prepareQuery(criteria, user);
        return isUnpaged(pageable) ? new PageImpl<>(this.getList(query)) : this.readPage(query, pageable, count, user);
    }

    private List<M> getList(SQLQuery<?> query) {
        return query.select(this.getQBean()).fetch();
    }

    protected Page<M> readPage(SQLQuery<?> query, Pageable pageable, long count, U user) {
        if (pageable.isPaged()) {
            query = this.setPageAndSize(query, pageable, user);
        }

        return PageableExecutionUtils.getPage(this.getList(query), pageable, count);
    }

    public <T> SQLQuery<T> setPageAndSize(SQLQuery<T> query, Pageable pageable, U user) {
        query.limit(pageable.getOffset() < 1 ? DEFAULT_SIZE : (int) pageable.getOffset());
        query.offset((Math.max(pageable.getPageNumber(), 0)) * pageable.getPageSize());
        return query;
    }

    @Transactional(readOnly = true)
    @Override
    public long getCount(C criteria, U user) {
        return this.prepareQuery(criteria, user).fetchCount();
    }

    @Transactional(readOnly = true)
    @Override
    public boolean isExist(C criteria, U user) {
        return this.getCount(criteria, user) > 0;
    }

    @Transactional(readOnly = true)
    @Override
    public boolean isNotExist(C criteria, U user) {
        return this.getCount(criteria, user) == 0;
    }

    @Transactional
    @Override
    public long delete(I id, U user) {
        AssertionUtils.notNull(id, "id should not be null.");
        return this.delete(Collections.singletonList(id), user);
    }

    @Transactional
    @Override
    public long delete(List<I> ids, U user) {
        AssertionUtils.notNull(ids, "ids should not be null.");
        C criteria = this.getEmptyCriteria();
        criteria.setId((IdFilter<I>) new IdFilter<I>().setIn(ids));

        return this.delete(criteria, user);
    }

    @Transactional
    @Override
    public long delete(C criteria, U user) {
        AssertionUtils.notNull(criteria, "Criteria object should not be null.");
        return this.getQueryFactory().delete(this.getEntity())
                .where(this.getIdentifierPath().in(this.getIds(criteria, user)))
                .execute();
    }

    @Transactional
    @Override
    public long directDelete(List<I> ids, U user) {
        AssertionUtils.notEmpty(ids, "ids should not be empty.");
        return this.getQueryFactory().delete(this.getEntity())
                .where(this.getIdentifierPath().in(ids))
                .execute();
    }

    @Transactional(readOnly = true)
    @Override
    public List<I> getIds(C criteria, U user) {
        return this.prepareQuery(criteria, user).select(this.getIdentifierPath()).fetch();
    }

    @Transactional(readOnly = true)
    @Override
    public List<M> get(C criteria, U user) {
        AssertionUtils.notNull(criteria, "Given criteria cannot be null");
        return this.prepareQuery(criteria, user).select(this.getQBean()).fetch();
    }

    @Transactional(readOnly = true)
    @Override
    public M getOne(C criteria, U user) {
        AssertionUtils.notNull(criteria, "Given criteria cannot be null");
        return this.prepareQuery(criteria, user).select(this.getQBean()).fetchOne();
    }

    protected <T> SQLQuery<T> setJoins(SQLQuery<T> query, U user) {
        return query;
    }

    public <R> SQLQuery<R> setOrders(SQLQuery<R> query, C criteria, U user) {
        return query;
    }

    public SQLQueryFactory getQueryFactory() {
        return queryFactory;
    }

    protected <I extends Comparable<? super I>> I safeFetchId(BaseModelAbstract<I> model) {
        return null == model ? null : model.getId();
    }

    protected <T, X extends Comparable<? super X>> SQLQuery<T> buildQuery(SQLQuery<T> query, Filter<X> filter, ComparableExpression<X> expression) {
        if (filter.getEquals() != null) {
            return query.where(expression.eq(filter.getEquals()));
        } else if (filter.getIn() != null) {
            return query.where(expression.in(filter.getIn()));
        } else if (filter.getNotEquals() != null) {
            return query.where(expression.ne(filter.getNotEquals()));
        } else if (filter.getNotIn() != null) {
            return query.where(expression.notIn(filter.getNotIn()));
        } else if (filter.getSpecified() != null) {
            if (filter.getSpecified()) {
                return query.where(expression.isNotNull());
            } else {
                return query.where(expression.isNull());
            }
        }
        return query;
    }

    protected <T> SQLQuery<T> buildQuery(SQLQuery<T> query, StringFilter filter, StringExpression expression) {
        if (filter.getEquals() != null) {
            return query.where(expression.eq(filter.getEquals()));
        } else if (filter.getIn() != null) {
            return query.where(expression.in(filter.getIn()));
        } else if (filter.getContains() != null) {
//            return likeUpperSpecification(metaclassFunction, filter.getContains());
            return query.where(expression.likeIgnoreCase("%" + filter.getContains() + "%"));
        } else if (filter.getDoesNotContain() != null) {
            return query.where(expression.notLike(filter.getDoesNotContain()));
        } else if (filter.getNotEquals() != null) {
            return query.where(expression.ne(filter.getNotEquals()));
        } else if (filter.getNotIn() != null) {
            return query.where(expression.notIn(filter.getNotIn()));
        } else if (filter.getSpecified() != null) {
            if (filter.getSpecified()) {
                return query.where(expression.isNotNull());
            } else {
                return query.where(expression.isNull());
            }
        }
        return query;
    }

    protected <T, X extends Comparable<? super X>> SQLQuery<T> buildQuery(SQLQuery<T> query, RangeFilter<X> filter,
                                                                          ComparableExpression<X> expression) {
        query = this.buildQueryInternal(query, filter, expression);

        if (filter.getGreaterThan() != null) {
            query.where(expression.gt(filter.getGreaterThan()));
        }
        if (filter.getGreaterThanOrEqual() != null) {
            query.where(expression.goe(filter.getGreaterThanOrEqual()));
        }
        if (filter.getLessThan() != null) {
            query.where(expression.lt(filter.getLessThan()));
        }
        if (filter.getLessThanOrEqual() != null) {
            query.where(expression.loe(filter.getLessThanOrEqual()));
        }
        return query;
    }

    private <T, X extends Comparable<? super X>> SQLQuery<T> buildQueryInternal(SQLQuery<T> query, RangeFilter<X> filter,
                                                                                ComparableExpressionBase<X> expression) {
        if (filter.getEquals() != null) {
            return query.where(expression.eq(filter.getEquals()));
        } else if (filter.getIn() != null) {
            return query.where(expression.in(filter.getIn()));
        }

        if (filter.getSpecified() != null) {
            if (filter.getSpecified()) {
                query.where(expression.isNotNull());
            } else {
                query.where(expression.isNull());
            }
        }
        if (filter.getNotEquals() != null) {
            query.where(expression.ne(filter.getNotEquals()));
        } else if (filter.getNotIn() != null) {
            query.where(expression.notIn(filter.getNotIn()));
        }
        return query;
    }

    protected <T, X extends Number & Comparable<? super X>> SQLQuery<T> buildQuery(SQLQuery<T> query, RangeFilter<X> filter,
                                                                                   NumberExpression<X> expression) {
        this.buildQueryInternal(query, filter, expression);

        if (filter.getGreaterThan() != null) {
            query.where(expression.gt(filter.getGreaterThan()));
        }
        if (filter.getGreaterThanOrEqual() != null) {
            query.where(expression.goe(filter.getGreaterThanOrEqual()));
        }
        if (filter.getLessThan() != null) {
            query.where(expression.lt(filter.getLessThan()));
        }
        if (filter.getLessThanOrEqual() != null) {
            query.where(expression.loe(filter.getLessThanOrEqual()));
        }
        return query;
    }
}
