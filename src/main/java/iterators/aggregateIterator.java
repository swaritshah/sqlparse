package iterators;

import helpers.CommonLib;
import helpers.PrimitiveValueWrapper;
import helpers.Schema;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.lang.reflect.Array;
import java.util.*;

import static helpers.CommonLib.castAs;

public class aggregateIterator implements RAIterator {
    //region Variables

    boolean getFromAggResults = false;

    private CommonLib commonLib = CommonLib.getInstance();
    private RAIterator child;
    private List<SelectItem> selectItems;
    private String tableAlias;
    private List<Column> groupByColumnReferences;
    private Map<String, String> aggColMap = new HashMap();
//    private List<String> aggValues = new ArrayList<String>();
    private List<PrimitiveValue> aggValues = new ArrayList<PrimitiveValue>() ;
    private Schema[] schema;
    boolean hasAvg = false;

    //endregion

    //region Constructor

    public aggregateIterator(RAIterator child, List<SelectItem> selectItems, String tableAlias) {

        this.child = child;
        this.selectItems = selectItems;
        this.tableAlias = tableAlias;
        this.schema = child.getSchema();

    }

    //endregion

    //region Iterator methods

    @Override
    public boolean hasNext() throws Exception {

        if (getFromAggResults)
            return false;

        return child.hasNext();
    }

    @Override
    public PrimitiveValue[] next() throws Exception {

        List<SelectItem> origSelectItems = selectItems;
        List<String> aggTypeOfSelectItems = getAggType(origSelectItems);

        PrimitiveValue[] tuple = child.next();

        if (tuple == null)
            return null;

        while (child.hasNext()) {

            if (tuple == null) {
                tuple = child.next();
                if (tuple == null)
                    continue;
            }

            aggAccumulator(tuple, aggTypeOfSelectItems);
            getFromAggResults = true;
            tuple = null; // current tuple has been processed.
        }


        if (aggValues.size() > 0) {
            getFromAggResults = true ;
            return convertedAggValues(aggTypeOfSelectItems) ;
        } else {
            return null;
        }
    }

    private PrimitiveValue[] convertedAggValues(List<String> aggType) throws PrimitiveValue.InvalidPrimitive {
        PrimitiveValue[] newTuple = new PrimitiveValue[aggType.size()] ;

        int k = 0 ;
        for(int j = 0; j < aggType.size() ; j++){
            if(aggType.get(j).toLowerCase().equals("avg")){
                double count = aggValues.get(k).toDouble() ;
                k++ ;
                double sum = aggValues.get(k).toDouble() ;
                double avg = sum/count ;
                newTuple[j] = new DoubleValue(avg);
            }
            else{
                newTuple[j] = aggValues.get(k);
            }
            k++ ;
        }
        return newTuple ;
    }

    private void aggAccumulator(PrimitiveValue[] tuple, List<String> aggType) throws PrimitiveValue.InvalidPrimitive {

        List<PrimitiveValue> newValues = new ArrayList<PrimitiveValue>();

            if (aggValues.size() > 0) {
                int j = 0 ;
                for (int index = 0; index < aggType.size(); index++) {
                    if (aggType.get(index).toLowerCase().equals("count")) {
                        newValues.add(new DoubleValue(aggValues.get(j).toDouble() + 1));
                    } else if (aggType.get(index).toLowerCase().equals("sum")) {
                        newValues.add(new DoubleValue(aggValues.get(j).toDouble() + tuple[index].toDouble()));
                    } else if (aggType.get(index).toLowerCase().equals("min")) {
                        double first = aggValues.get(j).toDouble();
                        double second = tuple[index].toDouble();
                        newValues.add(new DoubleValue(Math.min(first, second)));
                    } else if (aggType.get(index).toLowerCase().equals("max")) {
                        double first = aggValues.get(j).toDouble();
                        double second = tuple[index].toDouble();
                        newValues.add(new DoubleValue(Math.max(first, second)));
                    } else if (aggType.get(index).toLowerCase().equals("avg")) {
                        PrimitiveValue count = new DoubleValue(aggValues.get(j).toDouble() + 1);
                        newValues.add(count);
                        j++ ;
                        PrimitiveValue sum = new DoubleValue(aggValues.get(j).toDouble() + tuple[index].toDouble());
                        newValues.add(sum);
                    }
                    j++ ;
                }
            } else {
                for (int index = 0; index < aggType.size(); index++) {
                    if (aggType.get(index).toLowerCase().equals("count")) {
                        newValues.add(new DoubleValue(1));
                    } else if (aggType.get(index).toLowerCase().equals("sum")) {
                        newValues.add(new DoubleValue(tuple[index].toDouble()));
                    } else if (aggType.get(index).toLowerCase().equals("min")) {
                        newValues.add(new DoubleValue(tuple[index].toDouble()));
                    } else if (aggType.get(index).toLowerCase().equals("max")) {
                        newValues.add(new DoubleValue(tuple[index].toDouble()));
                    } else if (aggType.get(index).toLowerCase().equals("avg")) {
                        hasAvg = true;
                        PrimitiveValue count = new DoubleValue(1);
                        newValues.add(count);
                        PrimitiveValue sum = new DoubleValue(tuple[index].toDouble());
                        newValues.add(sum);
                    }
                }

            }
            aggValues.clear();
            aggValues.addAll(newValues) ;
        }


    private List<String> getAggType(List<SelectItem> selectItems) {
        List<String> list = new ArrayList<String>();

        for (int index = 0; index < selectItems.size(); index++) {
            Function function = null;
            if ((function = (Function) CommonLib.castAs(((SelectExpressionItem) selectItems.get(index)).getExpression(), Function.class)) != null)
                list.add(function.getName());
        }
        return list;
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
        this.child = child ;
    }

    @Override
    public Schema[] getSchema() {
        return this.schema ;
    }

    @Override
    public void setSchema(Schema[] schema) {
        this.schema = schema ;
    }

    @Override
    public RAIterator optimize(RAIterator iterator)
    {
        RAIterator child = iterator.getChild();
        child = child.optimize(child);
        iterator.setChild(child);
        return iterator;
    }

   //endregion
}
