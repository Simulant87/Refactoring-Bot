package de.refactoringbot.refactorings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.SoftAssertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.javadoc.JavadocBlockTag;

import de.refactoringbot.model.botissue.BotIssue;
import de.refactoringbot.model.configuration.GitConfiguration;
import de.refactoringbot.model.exceptions.BotRefactoringException;
import de.refactoringbot.refactoring.RefactoringHelper;
import de.refactoringbot.refactoring.supportedrefactorings.RemoveMethodParameter;
import de.refactoringbot.resources.removeparameter.TestDataClassRemoveParameter;

public class RemoveParameterTest extends AbstractRefactoringTests {

	private static final Logger logger = LoggerFactory.getLogger(RemoveParameterTest.class);
	private TestDataClassRemoveParameter removeParameterTestClass = new TestDataClassRemoveParameter();

	@Rule
	public final ExpectedException exception = ExpectedException.none();

	@Override
	public Class<TestDataClassRemoveParameter> getTestResourcesClass() {
		return TestDataClassRemoveParameter.class;
	}

	@Test
	public void testRemoveUnusedParameter() throws Exception {
		testRemoveParameter(removeParameterTestClass.getLineOfMethodWithUnusedParameter(0, 0, 0), "b");
	}

	@Test
	public void testRemoveUsedParameter() throws Exception {
		exception.expect(BotRefactoringException.class);
		testRemoveParameter(removeParameterTestClass.getLineOfMethodWithUnusedParameter(0, 0, 0), "a");
	}

	@Test
	public void testRemoveNotExistingParameter() throws Exception {
		exception.expect(BotRefactoringException.class);
		testRemoveParameter(removeParameterTestClass.getLineOfMethodWithUnusedParameter(0, 0, 0), "d");
	}

	@Test
	public void testRemoveUnusedParameter2() throws Exception {
		testRemoveParameter(removeParameterTestClass.getLineOfMethodWithUnusedParameter2(0), "a");
	}

	/**
	 * Tries to remove the parameter with the given name from the method in the
	 * given line. Checks if removal was successful and if the additional dummy
	 * method in the test class remained the same
	 * 
	 * @param lineNumberOfMethodWithParameterToBeRemoved
	 * @param parameterName
	 * @throws Exception
	 */
	private void testRemoveParameter(int lineNumberOfMethodWithParameterToBeRemoved, String parameterName)
			throws Exception {
		// arrange
		File tempFile = getTempCopyOfTestResourcesFile();
		BotIssue issue = new BotIssue();
		GitConfiguration gitConfig = new GitConfiguration();
		RemoveMethodParameter refactoring = new RemoveMethodParameter();
		CompilationUnit cuOriginalFile = JavaParser.parse(tempFile);
		MethodDeclaration originalMethod = RefactoringHelper
				.getMethodByLineNumberOfMethodName(lineNumberOfMethodWithParameterToBeRemoved, cuOriginalFile);
		MethodDeclaration originalDummyMethod = RefactoringHelper.getMethodByLineNumberOfMethodName(
				removeParameterTestClass.getLineNumberOfDummyMethod(0, 0, 0), cuOriginalFile);
		MethodDeclaration originalCallerMethod = RefactoringHelper
				.getMethodByLineNumberOfMethodName(removeParameterTestClass.getLineNumberOfCaller(), cuOriginalFile);
		
		SoftAssertions softAssertions = new SoftAssertions();
		softAssertions.assertThat(originalMethod).isNotNull();
		softAssertions.assertThat(originalDummyMethod).isNotNull();
		softAssertions.assertThat(originalCallerMethod).isNotNull();
		softAssertions.assertAll();

		gitConfig.setRepoFolder("");
		issue.setFilePath(tempFile.getAbsolutePath());
		issue.setLine(lineNumberOfMethodWithParameterToBeRemoved);
		issue.setJavaRoots(new ArrayList<>());
		issue.setRefactorString(parameterName);
		issue.setAllJavaFiles(Arrays.asList(tempFile.getAbsolutePath()));

		// act
		String outputMessage = refactoring.performRefactoring(issue, gitConfig);
		logger.info(outputMessage);

		// assert
		String methodName = originalMethod.getNameAsString();
		String dummyMethodName = originalDummyMethod.getNameAsString();
		String callerMethodName = originalCallerMethod.getNameAsString();
		FileInputStream in = new FileInputStream(tempFile);
		CompilationUnit cu = JavaParser.parse(in);
		MethodDeclaration refactoredMethod = getMethodByName(methodName, cu);

		// assert that parameter has been removed from the target method
		assertThat(refactoredMethod).isNotNull();
		assertThat(refactoredMethod.getParameterByName(parameterName).isPresent()).isFalse();

		// assert that parameter has also been removed from the javadoc
		List<JavadocBlockTag> javadocBlockTags = refactoredMethod.getJavadoc().get().getBlockTags();
		for (JavadocBlockTag javadocBlockTag : javadocBlockTags) {
			if (javadocBlockTag.getTagName().equals("param")) {
				assertThat(javadocBlockTag.getName().get()).isNotEqualTo(parameterName);
			}
		}

		// assert that dummy method is unchanged
		MethodDeclaration dummyMethod = getMethodByName(dummyMethodName, cu);
		assertThat(dummyMethod).isNotNull();
		assertThat(dummyMethod.getParameterByName(parameterName)).isPresent();

		// assert that caller method has been refactored as well
		MethodDeclaration callerMethod = getMethodByName(callerMethodName, cu);
		assertThat(callerMethod).isNotNull();
		for (MethodCallExpr methodCall : callerMethod.getBody().get().findAll(MethodCallExpr.class)) {
			if (methodCall.getNameAsString().equals(refactoredMethod.getNameAsString())) {
				NodeList<Expression> callerMethodArguments = methodCall.getArguments();
				NodeList<Parameter> refactoredMethodParameters = refactoredMethod.getParameters();

				assertThat(callerMethodArguments).hasSameSizeAs(refactoredMethodParameters);
			}
		}
	}

	/**
	 * TEST HELPER METHOD ONLY. Does not work for classes with with more than one
	 * method declaration with the same name.
	 * 
	 * Finds a method in a compilation unit with a specific name.
	 * 
	 * @param methodName
	 * @param cu
	 * @return MethodDeclaration or null if none found
	 */
	private MethodDeclaration getMethodByName(String methodName, CompilationUnit cu) {
		MethodDeclaration result = null;
		List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
		for (MethodDeclaration method : methods) {
			if (method.getNameAsString().equals(methodName)) {
				result = method;
			}
		}
		return result;
	}

}
