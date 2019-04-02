package iterators;

import helpers.CommonLib;
import helpers.PrimitiveValueWrapper;
import helpers.Schema;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.PrimitiveValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
/*import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;*/

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;

public class FilterIterator implements RAIterator
{
   //region Variables

   //private static final Logger logger = LogManager.getLogger();
   private CommonLib commonLib = CommonLib.getInstance();

   private RAIterator child;
   private Expression expression;
   private Schema[] schema ;
   private PrimitiveValue[] tuple;


   //endregion

   //region Constructor

   public FilterIterator(RAIterator child,Expression expression)
   {

      this.child = child;
      this.expression = expression;
      this.schema = child.getSchema();

      commonLib.getExpressionList(expression);
      commonLib.getColumnList(expression);
   }

   //endregion

   //region Iterator methods

   public boolean hasNext() throws Exception {

      while (child.hasNext()) {
         tuple = child.next();
         if (tuple == null)
            return false;
         PrimitiveValueWrapper[] wrappedTuple = commonLib.convertTuplePrimitiveValueToPrimitiveValueWrapperArray(tuple, this.schema);
         if (commonLib.eval(expression, wrappedTuple).getPrimitiveValue().toBool())
            return true;
      }

      return false;
   }

   @Override
   public PrimitiveValue[] next() throws Exception {
      return tuple;
   }

   @Override
   public void reset() throws Exception
   {
      child.reset();
   }

   @Override
   public RAIterator getChild() {
      return this.child ;
   }

   @Override
   public void setChild(RAIterator child) {
      this.child = child ;
   }


   @Override
   public Schema[] getSchema() {
      return this.schema;
   }

   @Override
   public void setSchema(Schema[] schema) {
      this.schema = schema ;
   }

   public Expression getExpression()
   {
      return expression;
   }

   public void setExpression(Expression expression){
      this.expression = expression ;
   }

   @Override
   public RAIterator optimize(RAIterator iterator)
   {
      FilterIterator filterIterator;
      FilterIterator childFilterIterator;
      MapIterator mapIterator;
      JoinIterator joinIterator;
      EqualsTo equalsTo;
      OrderByIterator orderByIterator;

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
               System.out.println("Selection pushed into projection successfully.");
            } catch (Exception e) {
               System.out.println("Error pushing selection into projection. Stacktrace: ");
               e.printStackTrace();
            }
         } else if ((joinIterator = (JoinIterator) CommonLib.castAs(filterIterator.getChild(),JoinIterator.class)) != null) {
            List<Expression> expressionList = commonLib.getExpressionList(filterIterator.getExpression());
            Schema[] leftSchema = joinIterator.getChild().getSchema();
            Schema[] rightSchema = joinIterator.getRightChild().getSchema();
            Expression onExpression = joinIterator.getOnExpression();
            RAIterator leftChild = joinIterator.getChild();
            RAIterator rightChild = joinIterator.getRightChild();
            Expression remainingExpression = null ;

            for (Expression expressionItem : expressionList) {
               if ((equalsTo = (EqualsTo) CommonLib.castAs(expressionItem,EqualsTo.class)) != null) {
                  if (commonLib.validateExpressionAgainstSchema(expressionItem,leftSchema)) {
                     leftChild = new FilterIterator(leftChild,expressionItem);
                     if (commonLib.validateExpressionAgainstSchema(expressionItem,rightSchema)) {
                        rightChild = new FilterIterator(rightChild,expressionItem);
                     }
                  }
                  else if (commonLib.validateExpressionAgainstSchema(expressionItem,rightSchema)) {
                     rightChild = new FilterIterator(rightChild,expressionItem);
                     if (commonLib.validateExpressionAgainstSchema(expressionItem,leftSchema)) {
                        leftChild = new FilterIterator(leftChild, expressionItem);
                     }
                  }
                  else if ((commonLib.validateExpressionAgainstSchema(equalsTo.getLeftExpression(),leftSchema)) && commonLib.validateExpressionAgainstSchema(equalsTo.getRightExpression(),rightSchema)) {
                     if (onExpression != null) {
                        onExpression = new AndExpression(onExpression,equalsTo);
                     } else {
                        onExpression = equalsTo;
                     }
                  }
                  else if ((commonLib.validateExpressionAgainstSchema(equalsTo.getLeftExpression(),rightSchema)) && commonLib.validateExpressionAgainstSchema(equalsTo.getRightExpression(),leftSchema)) {
                     if (onExpression != null) {
                        onExpression = new AndExpression(onExpression,equalsTo);
                     } else {
                        onExpression = equalsTo;
                     }
                  }
                  else{
                     if (remainingExpression != null) {
                        remainingExpression = new AndExpression(remainingExpression,expressionItem);
                     } else {
                        remainingExpression = expressionItem;
                     }
                  }
               }
               else if (commonLib.validateExpressionAgainstSchema(expressionItem,leftSchema)) {
                     leftChild = new FilterIterator(leftChild,expressionItem);
               }

               else if (commonLib.validateExpressionAgainstSchema(expressionItem,rightSchema)) {
                     rightChild = new FilterIterator(rightChild,expressionItem);
               }
               else{
                  if (remainingExpression != null) {
                     remainingExpression = new AndExpression(remainingExpression,expressionItem);
                  } else {
                     remainingExpression = expressionItem;
                  }
               }
            }

            iterator = new JoinIterator(leftChild,rightChild,onExpression);

            if(remainingExpression != null){
               RAIterator child = iterator.getChild();
               child = child.optimize(child);
               iterator.setChild(child);
               RAIterator newIterator = new FilterIterator(iterator, remainingExpression) ;
               return newIterator ;
            }


         } else if ((childFilterIterator = (FilterIterator) CommonLib.castAs(filterIterator.getChild(),FilterIterator.class)) != null) {
            iterator = new FilterIterator(childFilterIterator.getChild(),new AndExpression(filterIterator.getExpression(),childFilterIterator.getExpression()));
            iterator = iterator.optimize(iterator);
         } else if ((orderByIterator = (OrderByIterator) CommonLib.castAs(filterIterator.getChild(),OrderByIterator.class)) != null) {
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

   //endregion
}
