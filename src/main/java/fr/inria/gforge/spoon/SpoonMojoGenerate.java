package fr.inria.gforge.spoon;

import fr.inria.gforge.spoon.configuration.SpoonConfigurationBuilder;
import fr.inria.gforge.spoon.configuration.SpoonConfigurationFactory;
import fr.inria.gforge.spoon.configuration.SpoonMavenPluginException;
import fr.inria.gforge.spoon.logging.ReportBuilder;
import fr.inria.gforge.spoon.logging.ReportFactory;
import fr.inria.gforge.spoon.metrics.PerformanceDecorator;
import fr.inria.gforge.spoon.metrics.SpoonLauncherDecorator;
import fr.inria.gforge.spoon.util.ClasspathHacker;
import fr.inria.gforge.spoon.util.LogWrapper;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.processing.ProcessorPropertiesImpl;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;
import java.util.stream.Stream;

@SuppressWarnings("UnusedDeclaration")
@Mojo(
		name = "generate",
		defaultPhase = LifecyclePhase.GENERATE_SOURCES,
		requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class SpoonMojoGenerate extends AbstractMojo {
	/**
	 * Tells to spoon that it should copy comments
	 */
	@Parameter(
			property = "enableComments",
			defaultValue = "true")
	private boolean enableComments;

	/**
	 * Input directory for Spoon.
	 */
	@Parameter(property = "folder.src")
	private File srcFolder;
	/**
	 * Input directories for Spoo,.
	 */
	@Parameter(property = "folder.src")
	private File[] srcFolders;
	/**
	 * Output directory where Spoon must generate his output (spooned source code).
	 */
	@Parameter(
			property = "folder.out",
			defaultValue = "${project.build.directory}/generated-sources/spoon")
	private File outFolder;
	/**
	 * Tells to spoon that it must not assume a full classpath.
	 */
	@Parameter(
			property = "noClasspath",
			defaultValue = "false")
	private boolean noClasspath;
	/**
	 * Tells to spoon we shouldn't rewrite source code in fully qualified mode.
	 */
	@Parameter(
			property = "withImports",
			defaultValue = "false")
	private boolean withImports;
	/**
	 * Tells to spoon that it must not assume a full classpath.
	 */
	@Parameter(
			property = "buildOnlyOutdatedFiles",
			defaultValue = "false")
	private boolean buildOnlyOutdatedFiles;
	/**
	 * Tells to spoon that it must not assume a full classpath.
	 */
	@Parameter(
			property = "noCopyResources",
			defaultValue = "false")
	private boolean noCopyResources;
	/**
	 * List of processors.
	 */
	@Parameter(property = "processors")
	private String[] processors;

	/**
	 * Active debug mode to see logs.
	 */
	@Parameter(
			property = "Debug mode",
			defaultValue = "false")
	private boolean debug;
	/**
	 * Active the compilation of original sources.
	 */
	@Parameter(
			property = "Compile original sources and not source spooned",
			defaultValue = "false")
	private boolean compileOriginalSources;
	/**
	 * Specify the java version for spoon.
	 */
	@Parameter(
			property = "Java version for spoon",
			defaultValue = "8")
	private int compliance;

	@Parameter(
			property = "Output type",
			defaultValue = "classes"
	)
	private String outputType;

	@Parameter(
			property = "Skip the generated sources as source input for Spoon",
			defaultValue = "false"
	)
	private boolean skipGeneratedSources;

	@Parameter(
			property = "Prevent the build from crashing in case of Spoon error: a warning will only be triggered",
			defaultValue = "false"
	)
	private boolean skipSpoonErrors;

	@Parameter
	private ProcessorProperties[] processorProperties;


    /**
     * Skip execution.
     * 
     * @since 2.6
     */
    @Parameter(
            property = "spoon.skip",
            defaultValue = "false")
    private boolean skip;

	/**
	 * Project spooned with maven information.
	 */
	@Parameter(
			defaultValue = "${project}",
			required = true,
			readonly = true)
	private MavenProject project;

	protected ReportBuilder reportBuilder;
	protected Launcher spoonLauncher;

	protected String[] buildArguments(SpoonConfigurationBuilder spoonConfigurationBuilder) throws SpoonMavenPluginException {
		spoonConfigurationBuilder.addInputFolder()
					.addOutputFolder()
					.addCompliance()
					.addNoClasspath()
					.addWithImports()
					.addBuildOnlyOutdatedFiles()
					.addNoCopyResources()
					.addSourceClasspath()
					.addEnableComments()
					.addProcessors()
					.addTemplates()
					.addOutputType();

		return spoonConfigurationBuilder.build();
	}

	protected void initMojo() throws Exception {
		// Initializes builders for report and config of spoon.
		try {
			this.reportBuilder = ReportFactory.newReportBuilder(this);
		} catch (RuntimeException e) {
			LogWrapper.warn(this, e.getMessage(), e);
			return;
		}
		final SpoonConfigurationBuilder spoonBuilder = SpoonConfigurationFactory.getConfig(this, this.reportBuilder);

		// Saves project name.
		this.reportBuilder.setProjectName(project.getName());
		this.reportBuilder.setModuleName(project.getName());

		addArtifactsInClasspathOfTargetClassLoader();

		// Initializes and launch launcher of spoon.
		this.spoonLauncher = new Launcher();

		this.spoonLauncher.setArgs(this.buildArguments(spoonBuilder));

		if (processorProperties != null) {
			this.initSpoonProperties(spoonLauncher);
		}
	}

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
	    if (this.skip) {
	        return ;
	    }

		if (project.getPackaging().equals("pom")) {
			return ;
		}

		try {
			this.initMojo();
			final SpoonLauncherDecorator performance = new PerformanceDecorator(this.reportBuilder, this.spoonLauncher);
			performance.execute();
			reportBuilder.buildReport();
		} catch (SpoonMavenPluginException e) {
	    	if (this.getSkipSpoonErrors()) {
				LogWrapper.warn(this, e.getMessage()+"\n This project will be ignored.", e);
			} else {
				throw new MojoExecutionException(e.getMessage(), e);
			}
		} catch (Exception e) {
			LogWrapper.error(this, e.getMessage(), e);
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	private void initSpoonProperties(Launcher launcher) throws MojoExecutionException {
		Environment environment = launcher.getEnvironment();

		for (ProcessorProperties processorProperties : this.getProcessorProperties()) {
			spoon.processing.ProcessorProperties properties = new ProcessorPropertiesImpl();

			Properties xmlProperties = processorProperties.getProperties();

			for (Object key : xmlProperties.keySet()) {
				String sKey = (String) key;
				String value = (String) xmlProperties.get(key);

				properties.set(sKey, value);
			}

			environment.setProcessorProperties(processorProperties.getName(), properties);
		}
	}

	private void addArtifactsInClasspathOfTargetClassLoader() throws IOException {
		// Changes classpath of the target class loader.
		if (project.getArtifacts() == null || project.getArtifacts().isEmpty()) {
			LogWrapper.info(this, "There is not artifact in this project.");
		} else {
			for (Artifact artifact : project.getArtifacts()) {
				LogWrapper.debug(this, artifact.toString());
				ClasspathHacker.addFile(artifact.getFile());
			}
		}

		// Displays final classpath of the target classloader.
		LogWrapper.info(this, "Running spoon with classpath:");
		final URL[] urlClassLoader = urlsFromClassLoader(ClassLoader.getSystemClassLoader());
		for (URL currentURL : urlClassLoader) {
			LogWrapper.info(this, currentURL.toString());
		}
	}

    private static URL[] urlsFromClassLoader(ClassLoader classLoader) {
        if (classLoader instanceof URLClassLoader) {
            return ((URLClassLoader) classLoader).getURLs();
        }
        return Stream.of(ManagementFactory.getRuntimeMXBean().getClassPath().split(File.pathSeparator))
                .map(SpoonMojoGenerate::toURL).toArray(URL[]::new);
    }

    private static URL toURL(String classPathEntry) {
        try {
            return new File(classPathEntry).toURI().toURL();
        }
        catch (MalformedURLException ex) {
            throw new IllegalArgumentException(
                    "URL could not be created from '" + classPathEntry + "'", ex);
        }
    }

	public File getSrcFolder() {
		return srcFolder;
	}

	public File[] getSrcFolders() {
		return srcFolders;
	}

	public File getOutFolder() {
		return outFolder;
	}

	public boolean isNoClasspath() {
		return noClasspath;
	}

	public boolean isWithImports() {
		return withImports;
	}

	public boolean isBuildOnlyOutdatedFiles() {
		return buildOnlyOutdatedFiles;
	}

	public boolean isNoCopyResources() {
		return noCopyResources;
	}

	public String[] getProcessorsPath() {
		return processors;
	}

	public boolean isDebug() {
		return debug;
	}

	public boolean isCompileOriginalSources() {
		return compileOriginalSources;
	}

	public int getCompliance() {
		return compliance;
	}

	public MavenProject getProject() {
		return project;
	}

	public void setEnableComments(boolean enableComments) {
		this.enableComments = enableComments;
	}

	public boolean isEnableComments() {
		return enableComments;
	}

	public ProcessorProperties[] getProcessorProperties() {
		return processorProperties;
	}

	public String getOutputType() {
		return outputType;
	}

	public boolean getSkipGeneratedSources() {
		return skipGeneratedSources;
	}

	public boolean getSkipSpoonErrors() {
		return skipSpoonErrors;
	}
}
