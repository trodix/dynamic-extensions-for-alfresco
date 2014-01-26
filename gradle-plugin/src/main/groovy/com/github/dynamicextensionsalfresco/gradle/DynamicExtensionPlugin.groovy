package com.github.dynamicextensionsalfresco.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.tooling.BuildException

/**
 * Gradle plugin that configures build settings for an Alfresco Dynamic Extension.
 * 
 * @author Laurens Fridael
 *
 */
class DynamicExtensionPlugin implements Plugin<Project> {

	@Override
	void apply(Project project) {
		configurePlugins(project)
		configureExtensions(project)
		configureInstallBundleTask(project)
		project.afterEvaluate {
			configureDependencies(project)
			configureRepositories(project)
			configureJarManifest(project)
		}
	}

	void configurePlugins(Project project) {
		project.apply plugin: "java"
		project.apply plugin: "osgi"
	}

	void configureExtensions(Project project) {
		project.convention.plugins[ProjectConvention.class.name] = new ProjectConvention(project)
		project.ext.username = project.has('username') ? project.username : null
		project.ext.password = project.has('password') ? project.password : null
	}

	void configureInstallBundleTask(Project project) {
		def task = project.tasks.create("installBundle")
		task.dependsOn("build")
		task.ext.installInDirectory = false
		task.ext.installInRepository = false
		task.ext.repository = [:] << Endpoint.DEFAULTS
		task.doFirst {
			if (installInDirectory) {
				File dir = new java.io.File(directory)
				if (!dir.exists()) {
					throw new BuildException("Directory '$directory' does not exist.", null)
					logger.error()
				} else if (!dir.isDirectory()) {
					throw new BuildException("'$directory' is not a directory", null)
				}
			}
		}
		task << {
			if (installInDirectory) {
				project.copy {
					from project.jar.archivePath
					into directory
				}
			}
			if (installInRepository) {
				BundleService bundleService = new BundleService()
				bundleService.client.with {
					endpoint.host = repository.host
					endpoint.port = repository.port
					endpoint.servicePath = repository.servicePath
					authentication.username = repository.username
					authentication.password = repository.password
				}
				try {
					def response = bundleService.installBundle project.jar.archivePath
					project.logger.info response.message
					project.logger.info "Bundle ID: ${response.bundleId}"
				} catch (RestClientException e) {
					if (e.status.code == 401) {
						throw new BuildException("User not authorized to install bundles in repository. " + 
							"Make sure you specify the correct username and password for an admin-level account.", e)
					} else if (e.status.code == 500) {
						throw new BuildException("Error installing bundle in repository: ${e.message}", e)
					}
				}
			}
		}
	}

	void configureDependencies(Project project) {
		def alfresco = [
			version: project.alfresco.version ?: Versions.ALFRESCO
		]
		def surf = [
			version: project.surf.version ?: Versions.SURF
		]
		def dynamicExtensions = [
			version: project.dynamicExtensions.version ?: Versions.DYNAMIC_EXTENSIONS
		]
		def spring = [
			version: project.surf.version ?: Versions.SURF
		]
		project.dependencies {
			compile ("org.alfresco:alfresco-core:${alfresco.version}") { transitive = false }
			compile ("org.alfresco:alfresco-repository:${alfresco.version}") { transitive = false }
			compile ("org.alfresco:alfresco-data-model:${alfresco.version}") { transitive = false }
			compile ("org.springframework.extensions.surf:spring-webscripts:${surf.version}") { transitive = false }
			compile ("org.springframework.extensions.surf:spring-surf-core:${surf.version}") { transitive = false }
			compile ("com.github.dynamicextensionsalfresco:annotations:${dynamicExtensions.version}") { transitive = false }
			compile ("com.github.dynamicextensionsalfresco:webscript-support:${dynamicExtensions.version}") { transitive = false }
			// Since Spring is so fundamental, this is the one dependency we leave as transitive.
			compile ('org.springframework:spring-context:3.0.0.RELEASE')
			// JSR-250 API contains the @Resource annotation for referencing dependencies by name.
			compile ('javax.annotation:jsr250-api:1.0') { transitive = false }
		}
	}

	void configureJarManifest(Project project) {
        project.jar {
            manifest {
                instruction "Alfresco-Dynamic-Extension", "true"
            }
        }
        /*
         * These packages must be imported for code that uses CGLIB or Spring AOP. For the sake of convenience, this
         * plugin preemptively adds these imports.
         *
         * Without these imports, you will get ClassNotFoundExceptions when using CGLIB proxies (generated by Spring)
         * for classes that are loaded within the OSGi container.
         *
         * BND will not be able to detect the use of CGLIB and Spring AOP classes at build-time, hence these packages
         * must be specified manually.
         *
         * If the task has already set the "Import-Package", we leave it as is.
         * Double import issue when writing an extension that explicitly references AOP classes.
         */
        if (!project.jar.manifest.instructionValue("Import-Package")) {
            def additionalPackages = [
                    "net.sf.cglib.core",
                    "net.sf.cglib.proxy",
                    "net.sf.cglib.reflect",
                    "org.aopalliance.aop",
                    "org.aopalliance.intercept",
                    "org.springframework.aop",
                    "org.springframework.aop.framework"
            ]
            project.jar {
                manifest {
                    instruction "Import-Package", "*," + additionalPackages.join(",")
                }
            }
        }
    }

	void configureRepositories(Project project) {
		project.repositories {
			mavenCentral()
			maven { url "https://artifacts.alfresco.com/nexus/content/groups/public" }
			maven { url "http://repo.springsource.org/release" }
			maven { url "https://raw.github.com/laurentvdl/dynamic-extensions-for-alfresco/mvn-repo/" }
		}
	}
}

class ProjectConvention {

	Project project
	def alfresco = [:]
	def surf = [:]
	def dynamicExtensions = [:]

	ProjectConvention(Project project) {
		this.project = project
	}

	void useAlfrescoVersion(String version) {
		project.alfresco.version = version
	}

	void useDynamicExtensionsVersion(String version) {
		project.dynamicExtensions.version = version
	}

	void useSurfVersion(String version) {
		project.surf.version = version
	}

	Task getInstallBundle() {
		project.tasks["installBundle"]
	}
	
	void toDirectory(String directory) {
		installBundle.ext.with {
			if (installInRepository) {
				throw new BuildException("Cannot use toDirectory() and toRepository() simultaneously.");
			}
			directory = directory
			installInDirectory = true
		}
	}

	void toRepository(Map options) {
		installBundle.ext.with {
			if (installInDirectory) {
				throw new BuildException("Cannot use toDirectory() and toRepository() simultaneously.");
			}
			repository << options
			installInRepository = true
		}
	}

}
