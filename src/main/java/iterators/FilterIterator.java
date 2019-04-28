package iterators;

import helpers.CommonLib;
import helpers.Index;
import helpers.PrimitiveValueWrapper;
import helpers.Schema;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.PrimitiveValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;

import java.util.List;

public class FilterIterator implements RAIterator {
    //region Variables

    //private static final Logger logger = LogManager.getLogger();
    private CommonLib commonLib = CommonLib.getInstance();

    private RAIterator child;
    private Expression expression;
    private Schema[] schema;
    private PrimitiveValue[] tuple;
    private boolean hasNextValue = false;

    //endregion

    //region Constructor

    public FilterIterator(RAIterator child, Expression expression) {

        this.child = child;
        this.expression = expression;
        this.schema = child.getSchema();

        commonLib.getExpressionList(expression);
        commonLib.getColumnList(expression);
    }

    //endregion

    //region Iterator methods

    public boolean hasNext() throws Exception {

        if (hasNextValue)
            return true;

        while (child.hasNext()) {
            tuple = child.next();
            if (tuple == null)
                return false;
            PrimitiveValueWrapper[] wrappedTuple = commonLib.convertTuplePrimitiveValueToPrimitiveValueWrapperArray(tuple, this.schema);
            if (commonLib.eval(expression, wrappedTuple).getPrimitiveValue().toBool()) {
                hasNextValue = true;
                return true;
            }
        }

        return false;
    }

    @Override
    public PrimitiveValue[] next() throws Exception {
        hasNextValue = false;
        return tuple;
    }

    @Override
    public void reset() throws Exception {
        child.reset();
    }

    @Override
    public RAIterator getChild() {
        return this.child;
    }

    @Override
    public void setChild(RAIterator child) {
        this.child = child;
    }


    @Override
    public Schema[] getSchema() {
        return this.schema;
    }

    @Override
    public void setSchema(Schema[] schema) {
        this.schema = schema;
    }

    public Expression getExpression() {
        return expression;
    }

    public void setExpression(Expression expression) {
        this.expression = expression;
    }

    @Override
    public RAIterator optimize(RAIterator iterator) {
        FilterIterator filterIterator;
        FilterIterator childFilterIterator;
        MapIterator mapIterator;
        JoinIterator joinIterator;
        EqualsTo equalsTo;
        OrderByIterator orderByIterator;
        TableIterator tableIterator;
        IndexIterator indexIterator;

        if ((filterIterator = (FilterIterator) CommonLib.castAs(iterator, FilterIterator.class)) != null) {
            if ((mapIterator = (MapIterator) CommonLib.castAs(filterIterator.getChild(), MapIterator.class)) != null) {
                try {
                    iterator = new MapIterator(
                            new FilterIterator(
                                    mapIterator.getChild(),
                                    filterIterator.getExpression()
                            ),
                            mapIterator.getSelectItems(),
                            mapIterator.getTableAlias()
                    );

                } catch (Exception e) {

                    e.printStackTrace();
                }
            } else if ((tableIterator = (TableIterator) CommonLib.castAs(filterIterator.getChild(), TableIterator.class)) != null) {
                if (hasIndexOnExpression(expression, tableIterator.getTableName())) {
                    try {
                        Schema[] schemas = filterIterator.getSchema();
                        String table = schemas[0].getTableName();
                        ColumnDefinition[] columnDefinitions = new ColumnDefinition[schemas.length];
                        int i = 0;
                        for (Schema schema : schemas) {
                            columnDefinitions[i++] = schema.getColumnDefinition();
                        }
                        //Expression exp = commonLib.getExpressionList(filterIterator.getExpression()).get(0);
                        filterIterator.setChild(new IndexIterator(table, table, columnDefinitions, expression));

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else if ((joinIterator = (JoinIterator) CommonLib.castAs(filterIterator.getChild(), JoinIterator.class)) != null) {
                List<Expression> expressionList = commonLib.getExpressionList(filterIterator.getExpression());
                Schema[] leftSchema = joinIterator.getChild().getSchema();
                Schema[] rightSchema = joinIterator.getRightChild().getSchema();
                Expression onExpression = joinIterator.getOnExpression();
                RAIterator leftChild = joinIterator.getChild();
                RAIterator rightChild = joinIterator.getRightChild();
                Expression remainingExpression = null;

                for (Expression expressionItem : expressionList) {
                    if ((equalsTo = (EqualsTo) CommonLib.castAs(expressionItem, EqualsTo.class)) != null) {
                        if (commonLib.validateExpressionAgainstSchema(expressionItem, leftSchema)) {
                            leftChild = new FilterIterator(leftChild, expressionItem);
                            if (commonLib.validateExpressionAgainstSchema(expressionItem, rightSchema)) {
                                rightChild = new FilterIterator(rightChild, expressionItem);
                            }
                        } else if (commonLib.validateExpressionAgainstSchema(expressionItem, rightSchema)) {
                            rightChild = new FilterIterator(rightChild, expressionItem);
                            if (commonLib.validateExpressionAgainstSchema(expressionItem, leftSchema)) {
                                leftChild = new FilterIterator(leftChild, expressionItem);
                            }
                        } else if ((commonLib.validateExpressionAgainstSchema(equalsTo.getLeftExpression(), leftSchema)) && commonLib.validateExpressionAgainstSchema(equalsTo.getRightExpression(), rightSchema)) {
                            if (onExpression != null) {
                                onExpression = new AndExpression(onExpression, equalsTo);
                            } else {
                                onExpression = equalsTo;
                            }
                        } else if ((commonLib.validateExpressionAgainstSchema(equalsTo.getLeftExpression(), rightSchema)) && commonLib.validateExpressionAgainstSchema(equalsTo.getRightExpression(), leftSchema)) {
                            if (onExpression != null) {
                                onExpression = new AndExpression(onExpression, equalsTo);
                            } else {
                                onExpression = equalsTo;
                            }
                        } else {
                            if (remainingExpression != null) {
                                remainingExpression = new AndExpression(remainingExpression, expressionItem);
                            } else {
                                remainingExpression = expressionItem;
                            }
                        }
                    } else if (commonLib.validateExpressionAgainstSchema(expressionItem, leftSchema)) {
                        leftChild = new FilterIterator(leftChild, expressionItem);
                    } else if (commonLib.validateExpressionAgainstSchema(expressionItem, rightSchema)) {
                        rightChild = new FilterIterator(rightChild, expressionItem);
                    } else {
                        if (remainingExpression != null) {
                            remainingExpression = new AndExpression(remainingExpression, expressionItem);
                        } else {
                            remainingExpression = expressionItem;
                        }
                    }
                }

                iterator = new JoinIterator(leftChild, rightChild, onExpression);

                if (remainingExpression != null) {
                    RAIterator child = iterator.getChild();
                    child = child.optimize(child);
                    iterator.setChild(child);
                    RAIterator newIterator = new FilterIterator(iterator, remainingExpression);
                    return newIterator;
                }


            } else if ((childFilterIterator = (FilterIterator) CommonLib.castAs(filterIterator.getChild(), FilterIterator.class)) != null) {
                iterator = new FilterIterator(childFilterIterator.getChild(), new AndExpression(filterIterator.getExpression(), childFilterIterator.getExpression()));
                iterator = iterator.optimize(iterator);
            } else if ((orderByIterator = (OrderByIterator) CommonLib.castAs(filterIterator.getChild(), OrderByIterator.class)) != null) {
                iterator = new OrderByIterator(
                        new FilterIterator(
                                orderByIterator.getChild(),
                                filterIterator.getExpression()
                        ),
                        orderByIterator.getOrderByElementsList(),
                        orderByIterator.getPlainSelect()
                );
            }
        }
        RAIterator child = iterator.getChild();
        child = child.optimize(child);
        iterator.setChild(child);
        return iterator;
    }

    private boolean hasIndexOnExpression(Expression expression, String tableName) {
        List<Expression> expressionList = commonLib.getExpressionList(expression);

        String[] indexes = Index.indexMap.get(tableName).split("\\|");


        for (String str : indexes)
            for(Expression expression1 : expressionList)
                for(Column column : commonLib.getColumnList(expression1))
                    if (column.getColumnName().equals(str))
                        return true;


        return false;
    }

    //endregion
}
