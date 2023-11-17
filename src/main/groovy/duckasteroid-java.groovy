import org.gradle.api.JavaVersion
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id 'java'
    id 'jacoco'
    id 'checkstyle'
    id 'pmd'
    id 'maven-publish'
    id 'signing'
}

// Which Java source and target we use
sourceCompatibility = JavaVersion.VERSION_14
targetCompatibility = JavaVersion.VERSION_14

// get dependencies from maven central
repositories {
    mavenCentral()
    mavenLocal()
}

// Test coverage - produce XML
jacocoTestReport {
    reports {
        xml.enabled true
    }
}

// Use the Checkstyle rules provided by the convention plugin
// Do not allow any warnings
configurations {
    checkstyleConfig
}

dependencies {
    checkstyleConfig("com.puppycrawl.tools:checkstyle:8.29") { transitive = false }
}

// setup checkstyle - use 8.29 and Google checks
checkstyle {
    toolVersion '8.29'
    sourceSets = [project.sourceSets.main] // don't check tests
    config = resources.text.fromArchiveEntry(configurations.checkstyleConfig, 'google_checks.xml')
    maxWarnings = 0
}

// configure PMD to output failures to console
pmd {
    consoleOutput = true
    sourceSets = [ project.sourceSets.main ] // don't check tests
}

/* sonarqube cloud
// TODO Re-instate sonarqube
//apply plugin: 'org.sonarqube'
sonarqube {
    properties {
        property "sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml"
    }
}
*/

// Publishing to Maven central requires javadoc and source JARs
java {
    withJavadocJar()
    withSourcesJar()
}

// HTML5 javadocs
javadoc {
    if(JavaVersion.current().isJava9Compatible()) {
        options.addBooleanOption('html5', true)
    }
}

// add the source and javadoc JARs to the artifact list
artifacts {
    archives javadocJar, sourcesJar
}

// Configure the publication so that all appropriate meta data ends up in the POM
// as required by Maven central
publishing {
    publications {
        mavenJava(MavenPublication) {
            from components.java
            pom {
                name = project.name
                //description = "Project ${getProject().path}"
                url = "http://duckasteroid.github.io/${rootProject.name}/"
                licenses {
                    license {
                        name = 'The Apache License, Version 2.0'
                        url = 'http://www.apache.org/licenses/LICENSE-2.0.txt'
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/duckAsteroid/${rootProject.name}.git"
                    developerConnection = "scm:git:ssh://github.com:duckAsteroid/${rootProject.name}.git"
                    url ="https://github.com/duckAsteroid/${rootProject.name}/tree/master"
                }
                developers {
                    developer {
                        id = 'duckAsteroid'
                        name = 'Chris Senior'
                        email ='christopher.senior@gmail.com'
                    }
                }
            }
        }
    }
    // add the Maven OSSRH repositories
    repositories {
        maven {
            name = 'OSSRH'
            // Release and snapshots have different URLs
            def releasesRepoUrl = 'https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/'
            def snapshotsRepoUrl = 'https://s01.oss.sonatype.org/content/repositories/snapshots/'
            url = version.endsWith('SNAPSHOT') ? snapshotsRepoUrl : releasesRepoUrl
            // use project properties to pick up user/pass for repo
            credentials {
                username = project.hasProperty('ossrhUsername') ?  ossrhUsername : "undefined"
                password = project.hasProperty('ossrhPassword') ?  ossrhPassword : "undefined"
            }
        }
        maven {
            name = "GitHubPackages"
            url = "https://maven.pkg.github.com/cognitranlimited/products-gradle-plugins"
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// All JARs published to Maven central must be signed
signing {
    sign publishing.publications.mavenJava
}
