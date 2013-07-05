/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.javaflow.ant;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.commons.javaflow.bytecode.transformation.ResourceTransformer;
import org.apache.commons.javaflow.bytecode.transformation.asm.AsmClassTransformer;
import org.apache.commons.javaflow.utils.RewritingUtils;
import org.apache.tools.ant.AntClassLoader;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.loader.AntClassLoader2;
import org.apache.tools.ant.taskdefs.ExecuteJava;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Commandline;
import org.apache.tools.ant.types.CommandlineJava;
import org.apache.tools.ant.types.Environment;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.PropertySet;
import org.apache.tools.ant.types.Reference;
import org.apache.tools.ant.types.Assertions;
import org.apache.tools.ant.types.Permissions;
import org.apache.tools.ant.types.RedirectorElement;
import org.apache.tools.ant.taskdefs.condition.Os;
import org.apache.tools.ant.util.ClasspathUtils;
import org.apache.tools.ant.util.KeepAliveInputStream;

/**
 * Ant task that enhances class files with javaflow instrumentation.
 */
public class AntRewriteTask extends MatchingTask {

	private static final String TIMEOUT_MESSAGE =
			"Timeout: killed the sub-process";
	private ResourceTransformer transformer;
	private File dstDir;
	private File srcDir;
	private Path compileClasspath;

	/**
	 * Directory to which the transformed files will be written.
	 * This can be the same as the source directory.
	 */
	public void setDestdir(final File pFile) {
		dstDir = pFile;
	}

	/**
	 * Directory from which the input files are read.
	 * This and the inherited {@link MatchingTask} forms an implicit
	 * {@link FileSet}.
	 */
	public void setSrcDir(final File pFile) {
		srcDir = pFile;
		fileset.setDir(srcDir);
	}

	/**
	 * Sets the transformer to use.
	 * <p/>
	 * <p/>
	 * This option is unpublished, because in a long run we'll
	 * likely to just focus on one transformer and get rid
	 * of the other (and this option will be removed then.)
	 *
	 * @param name "ASM". Case insensitive.
	 */
	public void setMode(String name) {
		if (name.equalsIgnoreCase("asm")) {
			transformer = new AsmClassTransformer();
		} else {
			throw new BuildException("Unrecognized mode: " + name);
		}
	}

	/**
	 * Gets the classpath to be used for this compilation.
	 *
	 * @return the class path
	 */
	public Path getClasspath() {
		return compileClasspath;
	}

	/**
	 * Set the classpath to be used for this compilation.
	 *
	 * @param classpath an Ant Path object containing the compilation classpath.
	 */
	public void setClasspath(Path classpath) {
		if (compileClasspath == null) {
			compileClasspath = classpath;
		} else {
			compileClasspath.append(classpath);
		}
	}

	public Path createClasspath() {
		if (compileClasspath == null) {
			compileClasspath = new Path(getProject());
		}
		return compileClasspath.createPath();
	}

	/**
	 * Set the classpath to use by reference.
	 *
	 * @param r a reference to an existing classpath.
	 */
	public void setClasspathRef(Reference r) {
		createClasspath().setRefid(r);
	}

	/**
	 * Check that all required attributes have been set and nothing
	 * silly has been entered.
	 *
	 * @since Ant 1.5
	 */
	protected void checkParameters() throws BuildException {
		checkDir(srcDir, "srcDir");
		checkDir(dstDir, "dstDir");
	}

	private void checkDir(final File pDir, final String pDescription) {
		if (pDir == null) {
			throw new BuildException("no " + pDescription + " directory is specified", getLocation());
		}
		if (!pDir.exists()) {
			throw new BuildException(pDescription + " directory \"" + pDir + "\" does not exist", getLocation());
		}
		if (!pDir.isDirectory()) {
			throw new BuildException(pDescription + " directory \"" + pDir + "\" is not a directory", getLocation());
		}
	}

	public void run() throws BuildException {
		ExecuteJava exe = new ExecuteJava();
		CommandlineJava command = new CommandlineJava();
		exe.setJavaCommand(command.getJavaCommand());
		exe.setClasspath(compileClasspath);
		exe.setSystemProperties(command.getSystemProperties());
		exe.setPermissions(new Permissions());
		exe.setTimeout(null);
		exe.execute(getProject());
		if (exe.killedProcess()) {
			throw new BuildException(TIMEOUT_MESSAGE);
		}
	}

	public void execute() throws BuildException {

		final DirectoryScanner ds = fileset.getDirectoryScanner(getProject());
		final String[] fileNames = ds.getIncludedFiles();
		ClassLoader cloader = new AntClassLoader(getProject(),compileClasspath);
		Thread.currentThread().setContextClassLoader(cloader);
		if (transformer == null) {
//			transformer =(AsmClassTransformer) ClasspathUtils.newInstance("org.apache.commons.javaflow.bytecode.transformation.asm.AsmClassTransformer",cloader);
			transformer = new AsmClassTransformer();
		}

		try {
			for (int i = 0; i < fileNames.length; i++) {
				final String fileName = fileNames[i];

				final File source = new File(srcDir, fileName);
				final File destination = new File(dstDir, fileName);

				if (!destination.getParentFile().exists()) {
					log("Creating dir: " + destination.getParentFile(), Project.MSG_VERBOSE);
					destination.getParentFile().mkdirs();
				}

				if (source.lastModified() < destination.lastModified()) {
					log("Omitting " + source + " as " + destination + " is up to date", Project.MSG_VERBOSE);
					continue;
				}

				if (fileName.endsWith(".class")) {
					log("Rewriting " + source + " to " + destination, Project.MSG_VERBOSE);
					// System.out.println("Rewriting " + source);

					RewritingUtils.rewriteClassFile(source, transformer, destination);
				}

				if (fileName.endsWith(".jar")
						|| fileName.endsWith(".ear")
						|| fileName.endsWith(".zip")
						|| fileName.endsWith(".war")) {

					log("Rewriting " + source + " to " + destination, Project.MSG_VERBOSE);

					RewritingUtils.rewriteJar(
							new JarInputStream(new FileInputStream(source)),
							transformer,
							new JarOutputStream(new FileOutputStream(destination))
					);

				}
			}
		} catch (IOException e) {
			throw new BuildException(e);
		}
	}
}
