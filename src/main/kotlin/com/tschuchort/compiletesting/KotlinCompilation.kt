/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tschuchort.compiletesting

import com.facebook.buck.jvm.java.javax.SynchronizedToolProvider
import io.github.classgraph.ClassGraph
import okio.Buffer
import org.jetbrains.kotlin.base.kapt3.AptMode
import org.jetbrains.kotlin.base.kapt3.KaptFlag
import org.jetbrains.kotlin.base.kapt3.KaptOptions
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.JVMAssertionsMode
import org.jetbrains.kotlin.config.JvmDefaultMode
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.Services
import java.io.*
import java.lang.RuntimeException
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.annotation.processing.Processor
import javax.tools.*


@Suppress("MemberVisibilityCanBePrivate")
class KotlinCompilation {
	/** Working directory for the compilation */
	var workingDir: File by default {
		val path = Files.createTempDirectory("Kotlin-Compilation")
		log("Created temporary working directory at ${path.toAbsolutePath()}")
		return@default path.toFile()
	}

	/** Arbitrary arguments to be passed to kapt */
	var kaptArgs: MutableMap<String, String> = mutableMapOf()

	/**
	 * Paths to directories or .jar files that contain classes
	 * to be made available in the compilation (i.e. added to
	 * the classpath)
	 */
	var classpaths: List<File> = emptyList()

	/** Source files to be compiled */
	var sources: List<SourceFile> = emptyList()

	/** Annotation processors to be passed to kapt */
	var annotationProcessors: List<Processor> = emptyList()

	/**
	 * Helpful information (if [verbose] = true) and the compiler
	 * system output will be written to this stream
	 */
	var messageOutputStream: OutputStream = NullStream

	/** Inherit classpath from calling process */
	var inheritClassPath: Boolean = false

	/** Include Kotlin runtime in to resulting .jar */
	var includeRuntime: Boolean = false

	/** Make kapt correct error types */
	var correctErrorTypes: Boolean = true

	/** Print verbose logging info */
	var verbose: Boolean = false

	/** Suppress all warnings */
	var suppressWarnings: Boolean = false

	/** All warnings should be treated as errors */
	var allWarningsAsErrors: Boolean = false

	/** Report locations of files generated by the compiler */
	var reportOutputFiles: Boolean = false

	/** Report on performance of the compilation */
	var reportPerformance: Boolean = false

	var loadBuiltInsFromDependencies: Boolean = false

	/** Name of the generated .kotlin_module file */
	var moduleName: String? = null

	/** Target version of the generated JVM bytecode */
	var jvmTarget: String = JvmTarget.DEFAULT.description

	/** Generate metadata for Java 1.8 reflection on method parameters */
	var javaParameters: Boolean = false

	/** Use the IR backend */
	var useIR: Boolean = false

	/** Paths where to find Java 9+ modules */
	var javaModulePath: Path? = null

	/**
	 * Root modules to resolve in addition to the initial modules,
	 * or all modules on the module path if <module> is ALL-MODULE-PATH
	 */
	var additionalJavaModules: MutableList<File> = mutableListOf()

	/** Don't generate not-null assertions for arguments of platform types */
	var noCallAssertions: Boolean = false

	/** Don't generate not-null assertion for extension receiver arguments of platform types */
	var noReceiverAssertions: Boolean = false

	/** Don't generate not-null assertions on parameters of methods accessible from Java */
	var noParamAssertions: Boolean = false

	/** Generate nullability assertions for non-null Java expressions */
	var strictJavaNullabilityAssertions: Boolean = false

	/** Disable optimizations */
	var noOptimize: Boolean = false

	/**
	 * Normalize constructor calls (disable: don't normalize; enable: normalize),
	 * default is 'disable' in language version 1.2 and below, 'enable' since language version 1.3
	 *
	 * {disable|enable}
	 */
	var constructorCallNormalizationMode: String? = null

	/** Assert calls behaviour {always-enable|always-disable|jvm|legacy} */
	var assertionsMode: String? = JVMAssertionsMode.DEFAULT.description

	/** Path to the .xml build file to compile */
	var buildFile: File? = null

	/** Compile multifile classes as a hierarchy of parts and facade */
	var inheritMultifileParts: Boolean = false

	/** Use type table in metadata serialization */
	var useTypeTable: Boolean = false

	/** Allow Kotlin runtime libraries of incompatible versions in the classpath */
	var skipRuntimeVersionCheck: Boolean = false

	/** Path to JSON file to dump Java to Kotlin declaration mappings */
	var declarationsOutputPath: File? = null

	/** Combine modules for source files and binary dependencies into a single module */
	var singleModule: Boolean = false

	/** Suppress the \"cannot access built-in declaration\" error (useful with -no-stdlib) */
	var suppressMissingBuiltinsError: Boolean = false

	/** Script resolver environment in key-value pairs (the value could be quoted and escaped) */
	var scriptResolverEnvironment: MutableMap<String, String> = mutableMapOf()

	/** Java compiler arguments */
	var javacArguments: MutableList<String> = mutableListOf()

	/** Package prefix for Java files */
	var javaPackagePrefix: String? = null

	/**
	 * Specify behavior for Checker Framework compatqual annotations (NullableDecl/NonNullDecl).
	 * Default value is 'enable'
	 */
	var supportCompatqualCheckerFrameworkAnnotations: String? = null

	/** Do not throw NPE on explicit 'equals' call for null receiver of platform boxed primitive type */
	var noExceptionOnExplicitEqualsForBoxedNull: Boolean = false

	/** Allow to use '@JvmDefault' annotation for JVM default method support.
	 * {disable|enable|compatibility}
	 * */
	var jvmDefault: String = JvmDefaultMode.DEFAULT.description

	/** Generate metadata with strict version semantics (see kdoc on Metadata.extraInt) */
	var strictMetadataVersionSemantics: Boolean = false

	/**
	 * Transform '(' and ')' in method names to some other character sequence.
	 * This mode can BREAK BINARY COMPATIBILITY and is only supposed to be used as a workaround
	 * of an issue in the ASM bytecode framework. See KT-29475 for more details
	 */
	var sanitizeParentheses: Boolean = false

	/** Paths to output directories for friend modules (whose internals should be visible) */
	var friendPaths: MutableList<File> = mutableListOf()

	/**
	 * Path to the JDK to be used
	 *
	 * If null, no JDK will be used with kotlinc (option -no-jdk)
	 * and the system java compiler will be used with empty bootclasspath
	 * (on JDK8) or --system none (on JDK9+). This can be useful if all
	 * the JDK classes you need are already on the (inherited) classpath.
	 * */
	var jdkHome: File? = if(inheritClassPath) null else getJdkHome()

	/**
	 * Path to the kotlin-stdlib.jar
	 * If none is given, it will be searched for in the host
	 * process' classpaths
	 */
	var kotlinStdLibJar: File? by default {
		findInHostClasspath(hostClasspaths, "kotlin-stdlib.jar",
			Regex("(kotlin-stdlib|kotlin-runtime)(-[0-9]+\\.[0-9]+\\.[0-9]+)\\.jar"))
	}

	/**
	 * Path to the kotlin-stdlib-jdk*.jar
	 * If none is given, it will be searched for in the host
	 * process' classpaths
	 */
	var kotlinStdLibJdkJar: File? by default {
		findInHostClasspath(hostClasspaths, "kotlin-stdlib-jdk*.jar",
			Regex("kotlin-stdlib-jdk[0-9]+(-[0-9]+\\.[0-9]+\\.[0-9]+)\\.jar"))
	}

	/**
	 * Path to the kotlin-reflect.jar
	 * If none is given, it will be searched for in the host
	 * process' classpaths
	 */
	var kotlinReflectJar: File? by default {
		findInHostClasspath(hostClasspaths, "kotlin-reflect.jar",
			Regex("kotlin-reflect(-[0-9]+\\.[0-9]+\\.[0-9]+)\\.jar"))
	}

	/**
	 * Path to the kotlin-script-runtime.jar
	 * If none is given, it will be searched for in the host
	 * process' classpaths
	 */
	var kotlinScriptRuntimeJar: File? by default {
		findInHostClasspath(hostClasspaths, "kotlin-script-runtime.jar",
			Regex("kotlin-script-runtime(-[0-9]+\\.[0-9]+\\.[0-9]+)\\.jar"))
	}

	/**
	 * Path to the kotlin-stdlib-common.jar
	 * If none is given, it will be searched for in the host
	 * process' classpaths
	 */
	var kotlinStdLibCommonJar: File? by default {
		findInHostClasspath(hostClasspaths, "kotlin-stdlib-common.jar",
			Regex("kotlin-stdlib-common(-[0-9]+\\.[0-9]+\\.[0-9]+)\\.jar"))
	}

	/**
	 * Path to the tools.jar file needed for kapt when using a JDK 8.
	 *
	 * Note: Using a tools.jar file with a JDK 9 or later leads to an
	 * internal compiler error!
	 */
	var toolsJar: File? by default {
        if (!isJdk9OrLater())
            jdkHome?.let { findToolsJarFromJdk(it) }
            ?: findInHostClasspath(hostClasspaths, "tools.jar", Regex("tools.jar"))
        else
            null
	}

	// Directory for input source files
	private val sourcesDir get() = workingDir.resolve("sources")

	// *.class files, Jars and resources (non-temporary) that are created by the
	// compilation will land here
	val classesDir get() = workingDir.resolve("classes")

	// Base directory for kapt stuff
	private val kaptBaseDir get() = workingDir.resolve("kapt")

	// Java annotation processors that are compile by kapt will put their generated files here
	val kaptSourceDir get() = kaptBaseDir.resolve("sources")

	// Output directory for Kotlin source files generated by kapt
	val kaptKotlinGeneratedDir get() = kaptArgs[OPTION_KAPT_KOTLIN_GENERATED]
			?.let { path ->
				require(File(path).isDirectory) { "$OPTION_KAPT_KOTLIN_GENERATED must be a directory" }
				File(path)
			}
			?: File(kaptBaseDir, "kotlinGenerated")

	val kaptStubsDir get() = kaptBaseDir.resolve("stubs")
	val kaptIncrementalDataDir get() = kaptBaseDir.resolve("incrementalData")

    /** ExitCode of the entire Kotlin compilation process */
    enum class ExitCode {
        OK, INTERNAL_ERROR, COMPILATION_ERROR, SCRIPT_EXECUTION_ERROR
    }

	/** Result of the compilation */
	class Result(val exitCode: ExitCode, val outputDirectory: File,
					  /** Messages that were printed by the compilation */
					  val messages: String) {
		/** All output files that were created by the compilation */
		val generatedFiles: Collection<File> = outputDirectory.listFilesRecursively()

		/** class loader to load the compile classes */
		val classLoader = URLClassLoader(arrayOf(outputDirectory.toURI().toURL()),
			this::class.java.classLoader)
	}


	// setup common arguments for the two kotlinc calls
	private fun commonK2JVMArgs() = K2JVMCompilerArguments().also { it ->
		it.destination = classesDir.absolutePath
		it.classpath = commonClasspaths().joinToString(separator = File.pathSeparator)

		if(jdkHome != null) {
			it.jdkHome = jdkHome!!.absolutePath
		}
		else {
			log("Using option -no-jdk. Kotlinc won't look for a JDK.")

			it.noJdk = true
		}

		it.verbose = verbose
		it.includeRuntime = includeRuntime

		// the compiler should never look for stdlib or reflect in the
		// kotlinHome directory (which is null anyway). We will put them
		// in the classpath manually if they're needed
		it.noStdlib = true
		it.noReflect = true

		if(moduleName != null)
			it.moduleName = moduleName

		it.jvmTarget = jvmTarget
		it.javaParameters = javaParameters
		it.useIR = useIR

		if(javaModulePath != null)
			it.javaModulePath = javaModulePath!!.toString()

		it.additionalJavaModules = additionalJavaModules.map(File::getAbsolutePath).toTypedArray()
		it.noCallAssertions = noCallAssertions
		it.noParamAssertions = noParamAssertions
		it.noReceiverAssertions = noReceiverAssertions
		it.strictJavaNullabilityAssertions = strictJavaNullabilityAssertions
		it.noOptimize = noOptimize

		if(constructorCallNormalizationMode != null)
			it.constructorCallNormalizationMode = constructorCallNormalizationMode

		if(assertionsMode != null)
			it.assertionsMode = assertionsMode

		if(buildFile != null)
			it.buildFile = buildFile!!.toString()

		it.inheritMultifileParts = inheritMultifileParts
		it.useTypeTable = useTypeTable

		if(declarationsOutputPath != null)
			it.declarationsOutputPath = declarationsOutputPath!!.toString()

		it.singleModule = singleModule

		if(javacArguments.isNotEmpty())
			it.javacArguments = javacArguments.toTypedArray()

		if(supportCompatqualCheckerFrameworkAnnotations != null)
			it.supportCompatqualCheckerFrameworkAnnotations = supportCompatqualCheckerFrameworkAnnotations

		it.jvmDefault = jvmDefault
		it.strictMetadataVersionSemantics = strictMetadataVersionSemantics
		it.sanitizeParentheses = sanitizeParentheses

		if(friendPaths.isNotEmpty())
			it.friendPaths = friendPaths.map(File::getAbsolutePath).toTypedArray()

		if(scriptResolverEnvironment.isNotEmpty())
			it.scriptResolverEnvironment = scriptResolverEnvironment.map { (key, value) -> "$key=\"$value\"" }.toTypedArray()

		it.noExceptionOnExplicitEqualsForBoxedNull = noExceptionOnExplicitEqualsForBoxedNull
		it.skipRuntimeVersionCheck = skipRuntimeVersionCheck
		it.suppressWarnings = suppressWarnings
		it.allWarningsAsErrors = allWarningsAsErrors
		it.reportOutputFiles = reportOutputFiles
		it.reportPerf = reportPerformance
		it.reportOutputFiles = reportOutputFiles
		it.loadBuiltInsFromDependencies = loadBuiltInsFromDependencies
	}

	/** Performs the 1st and 2nd compilation step to generate stubs and run annotation processors */
	private fun stubsAndApt(): ExitCode {
		if(annotationProcessors.isEmpty()) {
			log("No services were given. Not running kapt steps.")
			return ExitCode.OK
		}

		val kaptOptions = KaptOptions.Builder().also {
			it.stubsOutputDir = kaptStubsDir
			it.sourcesOutputDir = kaptSourceDir
			it.incrementalDataOutputDir = kaptIncrementalDataDir
			it.classesOutputDir = classesDir
			it.processingOptions.apply {
				putAll(kaptArgs)
				putIfAbsent(OPTION_KAPT_KOTLIN_GENERATED, kaptKotlinGeneratedDir.absolutePath)
			}

			it.mode = AptMode.STUBS_AND_APT
			it.flags.add(KaptFlag.MAP_DIAGNOSTIC_LOCATIONS)
		}

		/* The kapt compiler plugin (KaptComponentRegistrar)
		 *  is instantiated by K2JVMCompiler using
		 *  a service locator. So we can't just pass parameters to it easily.
		 *  Instead we need to use a thread-local global variable to pass
		 *  any parameters that change between compilations
		 */
		KaptComponentRegistrar.threadLocalParameters.set(
				KaptComponentRegistrar.Parameters(annotationProcessors, kaptOptions)
		)

		val kotlinSources = sourcesDir.listFilesRecursively().filter<File>(File::isKotlinFile)
		val javaSources = sourcesDir.listFilesRecursively().filter(File::isJavaFile)

		val sourcePaths = mutableListOf<File>().apply {
			addAll(javaSources)

			if(kotlinSources.isNotEmpty()) {
				addAll(kotlinSources)
			}
			else {
				/* __HACK__: The K2JVMCompiler expects at least one Kotlin source file or it will crash.
                   We still need kapt to run even if there are no Kotlin sources because it executes APs
                   on Java sources as well. Alternatively we could call the JavaCompiler instead of kapt
                   to do annotation processing when there are only Java sources, but that's quite a lot
                   of work (It can not be done in the compileJava step because annotation processors on
                   Java files might generate Kotlin files which then need to be compiled in the
                   compileKotlin step before the compileJava step). So instead we trick K2JVMCompiler
                   by just including an empty .kt-File. */
				add(SourceFile.new("emptyKotlinFile.kt", "").writeIfNeeded(kaptBaseDir))
			}
		}.map(File::getAbsolutePath).distinct()

		if(!isJdk9OrLater()) {
			try {
				Class.forName("com.sun.tools.javac.util.Context")
			}
			catch (e: ClassNotFoundException) {
				require(toolsJar != null) {
					"toolsJar must not be null on JDK 8 or earlier if it's classes aren't already on the classpath"
				}

				require(toolsJar!!.exists()) { "toolsJar file does not exist" }
				(ClassLoader.getSystemClassLoader() as URLClassLoader).addUrl(toolsJar!!.toURI().toURL())
			}
		}

		val resourcesUri = URI.create(
			this::class.java.classLoader
				.getResource("META-INF/services/org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar")
				?.toString()?.removeSuffix("/META-INF/services/org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar")
				?: throw AssertionError("Could not get path to ComponentRegistrar service from META-INF")
		)

		val resourcesPath = when(resourcesUri.scheme) {
			"jar" -> Paths.get(URI.create(resourcesUri.schemeSpecificPart.removeSuffix("!")))
			"file" -> Paths.get(resourcesUri)
			else -> throw IllegalStateException(
				"Don't know how to handle protocol of ComponentRegistrar plugin. " +
						"Did you include this library in a weird way? Only jar and file path are supported."
			)
		}.toAbsolutePath()

		val k2JvmArgs = commonK2JVMArgs().also {
			it.freeArgs = sourcePaths

			it.pluginClasspaths = (it.pluginClasspaths?.toList() ?: emptyList<String>() + resourcesPath.toString())
					.distinct().toTypedArray()
		}

		val compilerMessageCollector = PrintingMessageCollector(
			internalMessageStream, MessageRenderer.WITHOUT_PATHS, true
		)

		return convertKotlinExitCode(
            K2JVMCompiler().exec(compilerMessageCollector, Services.EMPTY, k2JvmArgs)
		)
	}

	/** Performs the 3rd compilation step to compile Kotlin source files */
	private fun compileKotlin(): ExitCode {
		val sources = sourcesDir.listFilesRecursively() +
				kaptKotlinGeneratedDir.listFilesRecursively() +
				kaptSourceDir.listFilesRecursively()

		// if no Kotlin sources are available, skip the compileKotlin step
		if(sources.filter<File>(File::isKotlinFile).isEmpty())
			return ExitCode.OK

		// in this step also include source files generated by kapt in the previous step
		val k2JvmArgs = commonK2JVMArgs().also {
			it.freeArgs = sources.map(File::getAbsolutePath).distinct()
		}

		val compilerMessageCollector = PrintingMessageCollector(
			internalMessageStream, MessageRenderer.WITHOUT_PATHS, true
		)

        return convertKotlinExitCode(
            	K2JVMCompiler().exec(compilerMessageCollector, Services.EMPTY, k2JvmArgs)
		)
	}

	/**
	 * 	Base javac arguments that only depend on the the arguments given by the user
	 *  Depending on which compiler implementation is actually used, more arguments
	 *  may be added
	 */
	private fun baseJavacArgs(isJavac9OrLater: Boolean) = mutableListOf<String>().apply {
		if(verbose) {
			add("-verbose")
			add("-Xlint:path") // warn about invalid paths in CLI
			add("-Xlint:options") // warn about invalid options in CLI

			if(isJavac9OrLater)
				add("-Xlint:module") // warn about issues with the module system
		}

		addAll("-d", classesDir.absolutePath)

		add("-proc:none") // disable annotation processing

		if(allWarningsAsErrors)
			add("-Werror")

		addAll(javacArguments)

		// also add class output path to javac classpath so it can discover
		// already compiled Kotlin classes
		addAll("-cp", (commonClasspaths() + classesDir)
			    .joinToString(File.pathSeparator, transform = File::getAbsolutePath))
	}

	/** Performs the 4th compilation step to compile Java source files */
	private fun compileJava(): ExitCode {
		val javaSources = (sourcesDir.listFilesRecursively() + kaptSourceDir.listFilesRecursively())
			    .filterNot<File>(File::isKotlinFile)

		if(javaSources.isEmpty())
			return ExitCode.OK

        if(jdkHome != null) {
            /* If a JDK home is given, try to run javac from there so it uses the same JDK
               as K2JVMCompiler. Changing the JDK of the system java compiler via the
               "--system" and "-bootclasspath" options is not so easy. */

            val jdkBinFile = File(jdkHome, "bin")
            check(jdkBinFile.exists()) { "No JDK bin folder found at: ${jdkBinFile.toPath()}" }

			val javacCommand = jdkBinFile.absolutePath + File.separatorChar + "javac"

			val isJavac9OrLater = isJavac9OrLater(getJavacVersionString(javacCommand))
			val javacArgs = baseJavacArgs(isJavac9OrLater)

            val javacProc = ProcessBuilder(listOf(javacCommand) + javacArgs + javaSources.map(File::getAbsolutePath))
					.directory(workingDir)
					.redirectErrorStream(true)
					.start()

			javacProc.inputStream.copyTo(internalMessageStream)
			javacProc.errorStream.copyTo(internalMessageStream)

            return when(javacProc.waitFor()) {
                0 -> ExitCode.OK
                1 -> ExitCode.COMPILATION_ERROR
                else -> ExitCode.INTERNAL_ERROR
            }
        }
        else {
            /*  If no JDK is given, we will use the host process' system java compiler
                and erase the bootclasspath. The user is then on their own to somehow
                provide the JDK classes via the regular classpath because javac won't
                work at all without them */

			val isJavac9OrLater = isJdk9OrLater()
			val javacArgs = baseJavacArgs(isJavac9OrLater).apply {
				// erase bootclasspath or JDK path because no JDK was specified
				if (isJavac9OrLater)
					addAll("--system", "none")
				else
					addAll("-bootclasspath", "")
			}

            log("jdkHome is null. Using system java compiler of the host process.")

            val javac = SynchronizedToolProvider.systemJavaCompiler
            val javaFileManager = javac.getStandardFileManager(null, null, null)
            val diagnosticCollector = DiagnosticCollector<JavaFileObject>()

            fun printDiagnostics() = diagnosticCollector.diagnostics.forEach { diag ->
                when(diag.kind) {
                    Diagnostic.Kind.ERROR -> error(diag.getMessage(null))
                    Diagnostic.Kind.WARNING,
                    Diagnostic.Kind.MANDATORY_WARNING -> warn(diag.getMessage(null))
                    else -> log(diag.getMessage(null))
                }
            }

            try {
                val noErrors = javac.getTask(
                    OutputStreamWriter(internalMessageStream), javaFileManager,
                    diagnosticCollector, javacArgs,
                    /* classes to be annotation processed */ null,
					javaSources.map { FileJavaFileObject(it) }
						.filter { it.kind == JavaFileObject.Kind.SOURCE }
                ).call()

                printDiagnostics()

                return if(noErrors)
                    ExitCode.OK
                else
                    ExitCode.COMPILATION_ERROR
            }
            catch (e: Exception) {
                if(e is RuntimeException || e is IllegalArgumentException) {
                    printDiagnostics()
                    error(e.toString())
                    return ExitCode.INTERNAL_ERROR
                }
                else
                    throw e
            }
        }
	}

	/** Runs the compilation task */
	fun compile(): Result {
		// make sure all needed directories exist
		sourcesDir.mkdirs()
		classesDir.mkdirs()
		kaptSourceDir.mkdirs()
		kaptStubsDir.mkdirs()
		kaptIncrementalDataDir.mkdirs()
		kaptKotlinGeneratedDir.mkdirs()

		// write given sources to working directory
		sources.forEach { it.writeIfNeeded(sourcesDir) }

		/*
		There are 4 steps to the compilation process:
		1. Generate stubs (using kotlinc with kapt plugin which does no further compilation)
		2. Run apt (using kotlinc with kapt plugin which does no further compilation)
		3. Run kotlinc with the normal Kotlin sources and Kotlin sources generated in step 2
		4. Run javac with Java sources and the compiled Kotlin classes
		 */

		/* Work around for warning that sometimes happens:
		"Failed to initialize native filesystem for Windows
		java.lang.RuntimeException: Could not find installation home path.
		Please make sure bin/idea.properties is present in the installation directory"
		See: https://github.com/arturbosch/detekt/issues/630
		*/
		withSystemProperty("idea.use.native.fs.for.win", "false") {
			// step 1 and 2: generate stubs and run annotation processors
			try {
				val exitCode = stubsAndApt()
				if (exitCode != ExitCode.OK) {
					val messages = internalMessageBuffer.readUtf8()
					searchSystemOutForKnownErrors(messages)
					return Result(exitCode, classesDir, messages)
				}
			} finally {
				KaptComponentRegistrar.threadLocalParameters.remove()
			}

			// step 3: compile Kotlin files
			compileKotlin().let { exitCode ->
				if(exitCode != ExitCode.OK) {
					val messages = internalMessageBuffer.readUtf8()
					searchSystemOutForKnownErrors(messages)
					return Result(exitCode, classesDir, messages)
				}
			}
		}

		// step 4: compile Java files
		compileJava().let { exitCode ->
			val messages = internalMessageBuffer.readUtf8()

			if(exitCode != ExitCode.OK)
				searchSystemOutForKnownErrors(messages)

			return Result(exitCode, classesDir, messages)
		}
	}

	private fun commonClasspaths() = mutableListOf<File>().apply {
		addAll(classpaths)
		addAll(listOfNotNull(kotlinStdLibJar, kotlinReflectJar, kotlinScriptRuntimeJar))

		if(inheritClassPath) {
			addAll(hostClasspaths)
			log("Inheriting classpaths:  " + hostClasspaths.joinToString(File.pathSeparator))
		}
	}.distinct()

	/** Searches compiler log for known errors that are hard to debug for the user */
	private fun searchSystemOutForKnownErrors(compilerSystemOut: String) {
		if(compilerSystemOut.contains("No enum constant com.sun.tools.javac.main.Option.BOOT_CLASS_PATH")) {
			warn(
				"${this::class.simpleName} has detected that the compiler output contains an error message that may be " +
						"caused by including a tools.jar file together with a JDK of version 9 or later. " +
						if (inheritClassPath)
							"Make sure that no tools.jar (or unwanted JDK) is in the inherited classpath"
						else ""
			)
		}

		if(compilerSystemOut.contains("Unable to find package java.")) {
			warn (
				"${this::class.simpleName} has detected that the compiler output contains an error message " +
						"that may be caused by a missing JDK. This can happen if jdkHome=null and inheritClassPath=false."
			)
		}
	}

	/** Tries to find a file matching the given [regex] in the host process' classpath */
	private fun findInHostClasspath(hostClasspaths: List<File>, simpleName: String, regex: Regex): File? {
		val jarFile = hostClasspaths.firstOrNull { classpath ->
			classpath.name.matches(regex)
			//TODO("check that jar file actually contains the right classes")
		}

		if (jarFile == null)
			log("Searched host classpaths for $simpleName and found no match")
		else
			log("Searched host classpaths for $simpleName and found ${jarFile.path}")

		return jarFile
	}

	private val hostClasspaths by lazy { getHostClasspaths() }

	/* This internal buffer and stream is used so it can be easily converted to a string
	that is put into the [Result] object, in addition to printing immediately to the user's
	stream. */
	private val internalMessageBuffer = Buffer()
	private val internalMessageStream = PrintStream(
		TeeOutputStream(
			object : OutputStream() {
				override fun write(b: Int) = messageOutputStream.write(b)
				override fun write(b: ByteArray?) = messageOutputStream.write(b)
				override fun write(b: ByteArray?, off: Int, len: Int) = messageOutputStream.write(b, off, len)
				override fun flush() = messageOutputStream.flush()
				override fun close() = messageOutputStream.close()
			},
			internalMessageBuffer.outputStream()
		)
	)

	private fun log(s: String) {
		if(verbose)
			internalMessageStream.println("logging: $s")
	}

	private fun warn(s: String) = internalMessageStream.println("warning: $s")
	private fun error(s: String) = internalMessageStream.println("error: $s")

	companion object {
		const val OPTION_KAPT_KOTLIN_GENERATED = "kapt.kotlin.generated"
    }
}

private fun convertKotlinExitCode(code: ExitCode) = when(code) {
    ExitCode.OK -> KotlinCompilation.ExitCode.OK
    ExitCode.INTERNAL_ERROR -> KotlinCompilation.ExitCode.INTERNAL_ERROR
    ExitCode.COMPILATION_ERROR -> KotlinCompilation.ExitCode.COMPILATION_ERROR
    ExitCode.SCRIPT_EXECUTION_ERROR -> KotlinCompilation.ExitCode.SCRIPT_EXECUTION_ERROR
}

/** Returns the files on the classloader's classpath and modulepath */
private fun getHostClasspaths(): List<File> {
	val classGraph = ClassGraph()
		.enableSystemJarsAndModules()
		.removeTemporaryFilesAfterScan()

	val classpaths = classGraph.classpathFiles
	val modules = classGraph.modules.mapNotNull { it.locationFile }

	return (classpaths + modules).distinctBy(File::getAbsolutePath)
}
