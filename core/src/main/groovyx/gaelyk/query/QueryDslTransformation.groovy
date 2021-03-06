package groovyx.gaelyk.query

import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import com.google.appengine.api.datastore.Query.FilterOperator
import org.codehaus.groovy.ast.expr.CastExpression

/**
 * This AST transformation makes two transformations at the AST level.
 * First of all, this transformation is applied only with the context of a closure
 * passed to the <code>datastore.query {}</code> or <code>datastore.execute {}</code> calls.
 * The two modifcations made on the AST are to transform the <code>where prop op value</code> calls
 * into a <code>where new WhereClause(prop, op, value)</code> call,
 * and the <code>from kindName as className</code>
 * into a <code>from kindName, className</code> call.
 *
 * @author Guillaume Laforge
 *
 * @since 1.0
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
class QueryDslTransformation implements ASTTransformation {

    /**
     * Visit the AST of the scripts and classes that contain datastore query/execute calls.
     *
     * @param nodes a null array since we use a global transformation
     * @param source the source unit on which we'll apply the transformations
     */
    void visit(ASTNode[] nodes, SourceUnit source) {

        def whereMethodVisitor = new ClassCodeVisitorSupport() {
            void visitMethodCallExpression(MethodCallExpression clauseCall) {

                // transform "where a op b" into "where WhereClause(a, op, b)"
                if (
                    clauseCall.method instanceof ConstantExpression &&
                    (clauseCall.method.value == "where" || clauseCall.method.value == "and") &&
                    clauseCall.arguments instanceof ArgumentListExpression &&
                    clauseCall.arguments.expressions.size() == 1 &&
                    clauseCall.arguments.expressions[0] instanceof BinaryExpression
                ) {
                    BinaryExpression binExpr = clauseCall.arguments.expressions[0]

                    // filter operator expression
                    ConstantExpression op = null
                    switch (binExpr.operation.text) {
                        case '==': op = new ConstantExpression('EQUAL');                 break
                        case '!=': op = new ConstantExpression('NOT_EQUAL');             break
                        case '<':  op = new ConstantExpression('LESS_THAN');             break
                        case '<=': op = new ConstantExpression('LESS_THAN_OR_EQUAL');    break
                        case '>':  op = new ConstantExpression('GREATER_THAN');          break
                        case '>=': op = new ConstantExpression('GREATER_THAN_OR_EQUAL'); break
                        case 'in': op = new ConstantExpression('IN');                    break
                    }
                    def operation = new PropertyExpression(new ClassExpression(ClassHelper.make(FilterOperator)), op)

                    clauseCall.arguments.expressions[0] = new ConstructorCallExpression(
                            ClassHelper.make(WhereClause),
                            new TupleExpression(new NamedArgumentListExpression([
                                    new MapEntryExpression(new ConstantExpression('column'), binExpr.leftExpression),
                                    new MapEntryExpression(new ConstantExpression('operation'), operation),
                                    new MapEntryExpression(new ConstantExpression('comparedValue'), binExpr.rightExpression)
                            ]))
                    )
                }

                // transform "from persons as Person" into "from persons, Person"
                if (
                    clauseCall.method instanceof ConstantExpression &&
                    clauseCall.method.value == "from"  &&
                    clauseCall.arguments instanceof ArgumentListExpression &&
                    clauseCall.arguments.expressions.size() == 1 &&
                    clauseCall.arguments.expressions[0] instanceof CastExpression
                ) {
                    CastExpression castExpr = clauseCall.arguments.expressions[0]
                    clauseCall.arguments.expressions[0] = castExpr.expression
                    clauseCall.arguments.expressions[1] = new ClassExpression(castExpr.type)
                }

                // continue the visit
                super.visitMethodCallExpression(clauseCall)
            }

            void visitClosureExpression(ClosureExpression expression) {
                super.visitClosureExpression(expression)
            }

            protected SourceUnit getSourceUnit() { source }
        }

        def queryMethodVisitor = new ClassCodeVisitorSupport() {
            void visitMethodCallExpression(MethodCallExpression call) {
                if (
                    // 'datastore' variable
                    call.objectExpression instanceof VariableExpression && call.objectExpression.variable == 'datastore' &&
                    // 'query' or 'execute' method
                    call.method instanceof ConstantExpression && (call.method.value == 'query' || call.method.value == 'execute') &&
                    // closure single argument
                    call.arguments.expressions.size() == 1 && call.arguments.expressions[0] instanceof ClosureExpression
                ) {
                    ClosureExpression closureExpr = call.arguments.expressions[0]
                    whereMethodVisitor.visitClosureExpression(closureExpr)
                } else {
                    super.visitMethodCallExpression(call)
                }
            }

            protected SourceUnit getSourceUnit() { source }
        }

        source.AST.classes.each { ClassNode cn ->
            queryMethodVisitor.visitClass(cn)
        }
    }
}
