/*
 * Copyright © 2010-2011 Philipp Eichhorn
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.handlers;

import static lombok.core.util.Arrays.isNotEmpty;
import static lombok.core.util.ErrorMessages.*;
import static lombok.core.util.Names.camelCase;
import static lombok.eclipse.handlers.Eclipse.*;
import static lombok.eclipse.handlers.ast.ASTBuilder.*;

import java.util.ArrayList;
import java.util.List;

import lombok.SwingInvokeAndWait;
import lombok.SwingInvokeLater;
import lombok.core.AnnotationValues;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.handlers.ThisReferenceReplaceVisitor;
import lombok.eclipse.handlers.ast.CallBuilder;
import lombok.eclipse.handlers.ast.StatementBuilder;
import lombok.eclipse.handlers.ast.TryBuilder;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.mangosdk.spi.ProviderFor;

/**
 * Handles the {@code lombok.SwingInvokeLater} and {@code lombok.SwingInvokeAndWait} annotation for eclipse.
 */
public class HandleSwingInvoke {
	@ProviderFor(EclipseAnnotationHandler.class)
	public static class HandleSwingInvokeLater implements EclipseAnnotationHandler<SwingInvokeLater> {
		@Override public boolean handle(AnnotationValues<SwingInvokeLater> annotation, Annotation ast, EclipseNode annotationNode) {
			return new HandleSwingInvoke().generateSwingInvoke("invokeLater", SwingInvokeLater.class, ast, annotationNode);
		}
	}

	@ProviderFor(EclipseAnnotationHandler.class)
	public static class HandleSwingInvokeAndWait implements EclipseAnnotationHandler<SwingInvokeAndWait> {
		@Override public boolean handle(AnnotationValues<SwingInvokeAndWait> annotation, Annotation ast, EclipseNode annotationNode) {
			return new HandleSwingInvoke().generateSwingInvoke("invokeAndWait", SwingInvokeAndWait.class, ast, annotationNode);
		}
	}

	public boolean generateSwingInvoke(String methodName, Class<? extends java.lang.annotation.Annotation> annotationType, ASTNode source, EclipseNode annotationNode) {
		final EclipseMethod method = EclipseMethod.methodOf(annotationNode);

		if (method == null) {
			annotationNode.addError(canBeUsedOnMethodOnly(annotationType));
			return true;
		}
		if (!method.wasCompletelyParsed()) {
			return false;
		}
		if (method.isAbstract() || method.isEmpty()) {
			annotationNode.addError(canBeUsedOnConcreteMethodOnly(annotationType));
			return true;
		}

		replaceWithQualifiedThisReference(method, source);

		String field = "$" + camelCase(method.name(), "runnable");

		CallBuilder elseStatementRun = Call(Name("java.awt.EventQueue"), methodName).withArgument(Name(field));

		StatementBuilder<? extends Statement> elseStatement;
		if ("invokeAndWait".equals(methodName)) {
			elseStatement =  Block().withStatement(generateTryCatchBlock(elseStatementRun, method));
		} else {
			elseStatement = Block().withStatement(elseStatementRun);
		}
				
		method.body(source, Block() //
			.withStatement(LocalDef(Type("java.lang.Runnable"), field).makeFinal().withInitialization(New(Type("java.lang.Runnable"), //
				ClassDef("").makeAnonymous().makeLocal() //
					.withMethod(MethodDef(Type("void"), "run").makePublic().withAnnotation(Annotation(Type("java.lang.Override"))) //
						.withStatements(method.get().statements))))) //
			.withStatement(If(Call(Name("java.awt.EventQueue"), "isDispatchThread")) //
				.Then(Block().withStatement(Call(Name(field), "run"))) //
				.Else(elseStatement)));

		method.rebuild();

		return true;
	}

	private TryBuilder generateTryCatchBlock(CallBuilder elseStatementRun, final EclipseMethod method) {
		return Try(Block() //
				.withStatement(elseStatementRun)) //
			.Catch(Arg(Type("java.lang.InterruptedException"), "$ex1"), Block()) //
			.Catch(Arg(Type("java.lang.reflect.InvocationTargetException"), "$ex2"), Block() //
				.withStatement(LocalDef(Type("java.lang.Throwable"), "$cause").makeFinal().withInitialization(Call(Name("$ex2"), "getCause")))
				.withStatements(rethrowStatements(method)) //
				.withStatement(Throw(New(Type("java.lang.RuntimeException")).withArgument(Name("$cause")))));
	}

	private List<StatementBuilder<? extends Statement>> rethrowStatements(final EclipseMethod method) {
		final List<StatementBuilder<? extends Statement>> rethrowStatements = new ArrayList<StatementBuilder<? extends Statement>>();
		if (isNotEmpty(method.get().thrownExceptions)) for (TypeReference thrownException : method.get().thrownExceptions) {
			rethrowStatements.add(If(InstanceOf(Name("$cause"), Type(thrownException))) //
				.Then(Throw(Cast(Type(thrownException), Name("$cause")))));
		}
		return rethrowStatements;
	}

	private void replaceWithQualifiedThisReference(final EclipseMethod method, final ASTNode source) {
		final EclipseNode parent = typeNodeOf(method.node());
		final TypeDeclaration typeDec = (TypeDeclaration)parent.get();
		final IReplacementProvider<Expression> replacement = new QualifiedThisReplacementProvider(new String(typeDec.name), source);
		new ThisReferenceReplaceVisitor(replacement).visit(method.get());
	}
}