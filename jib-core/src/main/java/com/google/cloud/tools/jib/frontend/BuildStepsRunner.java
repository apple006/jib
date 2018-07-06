/*
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.frontend;

import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.cloud.tools.jib.builder.BuildConfiguration;
import com.google.cloud.tools.jib.builder.BuildLogger;
import com.google.cloud.tools.jib.builder.BuildSteps;
import com.google.cloud.tools.jib.builder.SourceFilesConfiguration;
import com.google.cloud.tools.jib.cache.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.cache.CacheDirectoryNotOwnedException;
import com.google.cloud.tools.jib.cache.CacheMetadataCorruptedException;
import com.google.cloud.tools.jib.cache.Caches;
import com.google.cloud.tools.jib.cache.Caches.Initializer;
import com.google.cloud.tools.jib.configuration.CacheConfiguration;
import com.google.cloud.tools.jib.registry.InsecureRegistryException;
import com.google.cloud.tools.jib.registry.RegistryAuthenticationFailedException;
import com.google.cloud.tools.jib.registry.RegistryUnauthorizedException;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import org.apache.http.conn.HttpHostConnectException;

/** Runs a {@link BuildSteps} and builds helpful error messages. */
public class BuildStepsRunner {

  /**
   * Creates a runner to build an image. Creates a directory for the cache, if needed.
   *
   * @param buildConfiguration the configuration parameters for the build
   * @return a {@link BuildStepsRunner} for building to a registry
   * @throws CacheDirectoryCreationException if the {@code cacheDirectory} could not be created
   */
  public static BuildStepsRunner forBuildImage(BuildConfiguration buildConfiguration)
      throws CacheDirectoryCreationException {
    return new BuildStepsRunner(
        BuildSteps.forBuildToDockerRegistry(
            buildConfiguration, getCacheInitializer(buildConfiguration)));
  }

  /**
   * Creates a runner to build to the Docker daemon. Creates a directory for the cache, if needed.
   *
   * @param buildConfiguration the configuration parameters for the build
   * @param sourceFilesConfiguration the source/destination file configuration for the image
   * @return a {@link BuildStepsRunner} for building to a Docker daemon
   * @throws CacheDirectoryCreationException if the {@code cacheDirectory} could not be created
   */
  public static BuildStepsRunner forBuildToDockerDaemon(
      BuildConfiguration buildConfiguration, SourceFilesConfiguration sourceFilesConfiguration)
      throws CacheDirectoryCreationException {
    return new BuildStepsRunner(
        BuildSteps.forBuildToDockerDaemon(
            buildConfiguration, getCacheInitializer(buildConfiguration)));
  }

  // TODO: Move this up to somewhere where defaults for cache location are provided and ownership is
  // checked rather than in Caches.Initializer.
  private static Initializer getCacheInitializer(BuildConfiguration buildConfiguration)
      throws CacheDirectoryCreationException {
    CacheConfiguration applicationLayersCacheConfiguration =
        buildConfiguration.getApplicationLayersCacheConfiguration() == null
            ? CacheConfiguration.makeTemporary()
            : buildConfiguration.getApplicationLayersCacheConfiguration();
    CacheConfiguration baseImageLayersCacheConfiguration =
        buildConfiguration.getBaseImageLayersCacheConfiguration() == null
            ? CacheConfiguration.forDefaultUserLevelCacheDirectory()
            : buildConfiguration.getBaseImageLayersCacheConfiguration();

    return new Caches.Initializer(
        baseImageLayersCacheConfiguration.getCacheDirectory(),
        applicationLayersCacheConfiguration.shouldEnsureOwnership(),
        applicationLayersCacheConfiguration.getCacheDirectory(),
        applicationLayersCacheConfiguration.shouldEnsureOwnership());
  }

  private static void handleRegistryUnauthorizedException(
      RegistryUnauthorizedException registryUnauthorizedException,
      BuildConfiguration buildConfiguration,
      HelpfulSuggestions helpfulSuggestions)
      throws BuildStepsExecutionException {
    if (registryUnauthorizedException.getHttpResponseException().getStatusCode()
        == HttpStatusCodes.STATUS_CODE_FORBIDDEN) {
      // No permissions for registry/repository.
      throw new BuildStepsExecutionException(
          helpfulSuggestions.forHttpStatusCodeForbidden(
              registryUnauthorizedException.getImageReference()),
          registryUnauthorizedException);

    } else {
      boolean isRegistryForBase =
          registryUnauthorizedException
              .getRegistry()
              .equals(buildConfiguration.getBaseImageRegistry());
      boolean isRegistryForTarget =
          registryUnauthorizedException
              .getRegistry()
              .equals(buildConfiguration.getTargetImageRegistry());
      boolean areBaseImageCredentialsConfigured =
          buildConfiguration.getBaseImageCredentialHelperName() != null
              || buildConfiguration.getKnownBaseRegistryCredentials() != null;
      boolean areTargetImageCredentialsConfigured =
          buildConfiguration.getTargetImageCredentialHelperName() != null
              || buildConfiguration.getKnownTargetRegistryCredentials() != null;

      if (isRegistryForBase && !areBaseImageCredentialsConfigured) {
        throw new BuildStepsExecutionException(
            helpfulSuggestions.forNoCredentialHelpersDefinedForBaseImage(
                registryUnauthorizedException.getRegistry()),
            registryUnauthorizedException);
      }
      if (isRegistryForTarget && !areTargetImageCredentialsConfigured) {
        throw new BuildStepsExecutionException(
            helpfulSuggestions.forNoCredentialHelpersDefinedForTargetImage(
                registryUnauthorizedException.getRegistry()),
            registryUnauthorizedException);
      }

      // Credential helper probably was not configured correctly or did not have the necessary
      // credentials.
      throw new BuildStepsExecutionException(
          helpfulSuggestions.forCredentialsNotCorrect(registryUnauthorizedException.getRegistry()),
          registryUnauthorizedException);
    }
  }

  private final BuildSteps buildSteps;

  @VisibleForTesting
  BuildStepsRunner(BuildSteps buildSteps) {
    this.buildSteps = buildSteps;
  }

  /**
   * Runs the {@link BuildSteps}.
   *
   * @param helpfulSuggestions suggestions to use in help messages for exceptions
   * @throws BuildStepsExecutionException if another exception is thrown during the build
   */
  public void build(HelpfulSuggestions helpfulSuggestions) throws BuildStepsExecutionException {
    try {
      // TODO: This logging should be injected via another logging class.
      BuildLogger buildLogger = buildSteps.getBuildConfiguration().getBuildLogger();

      buildLogger.lifecycle("");
      buildLogger.lifecycle(buildSteps.getStartupMessage());

      // Logs the different source files used.
      buildLogger.info("Containerizing application with the following files:");

      buildLogger.info("\tClasses:");
      // TODO: Don't use the indexes.
      buildSteps
          .getBuildConfiguration()
          .getLayerConfigurations()
          .get(2)
          .getLayerEntries()
          .get(0)
          .getSourceFiles()
          .forEach(classesFile -> buildLogger.info("\t\t" + classesFile));

      buildLogger.info("\tResources:");
      buildSteps
          .getBuildConfiguration()
          .getLayerConfigurations()
          .get(1)
          .getLayerEntries()
          .get(0)
          .getSourceFiles()
          .forEach(resourceFile -> buildLogger.info("\t\t" + resourceFile));

      buildLogger.info("\tDependencies:");
      buildSteps
          .getBuildConfiguration()
          .getLayerConfigurations()
          .get(0)
          .getLayerEntries()
          .get(0)
          .getSourceFiles()
          .forEach(dependencyFile -> buildLogger.info("\t\t" + dependencyFile));

      buildSteps.run();

      buildLogger.lifecycle("");
      buildLogger.lifecycle(buildSteps.getSuccessMessage());

    } catch (CacheMetadataCorruptedException cacheMetadataCorruptedException) {
      throw new BuildStepsExecutionException(
          helpfulSuggestions.forCacheNeedsClean(), cacheMetadataCorruptedException);

    } catch (ExecutionException executionException) {
      BuildConfiguration buildConfiguration = buildSteps.getBuildConfiguration();

      Throwable exceptionDuringBuildSteps = executionException.getCause();

      if (exceptionDuringBuildSteps instanceof HttpHostConnectException) {
        // Failed to connect to registry.
        throw new BuildStepsExecutionException(
            helpfulSuggestions.forHttpHostConnect(), exceptionDuringBuildSteps);

      } else if (exceptionDuringBuildSteps instanceof RegistryUnauthorizedException) {
        handleRegistryUnauthorizedException(
            (RegistryUnauthorizedException) exceptionDuringBuildSteps,
            buildConfiguration,
            helpfulSuggestions);

      } else if (exceptionDuringBuildSteps instanceof RegistryAuthenticationFailedException
          && exceptionDuringBuildSteps.getCause() instanceof HttpResponseException) {
        handleRegistryUnauthorizedException(
            new RegistryUnauthorizedException(
                buildConfiguration.getTargetImageRegistry(),
                buildConfiguration.getTargetImageRepository(),
                (HttpResponseException) exceptionDuringBuildSteps.getCause()),
            buildConfiguration,
            helpfulSuggestions);

      } else if (exceptionDuringBuildSteps instanceof UnknownHostException) {
        throw new BuildStepsExecutionException(
            helpfulSuggestions.forUnknownHost(), exceptionDuringBuildSteps);

      } else if (exceptionDuringBuildSteps instanceof InsecureRegistryException) {
        throw new BuildStepsExecutionException(
            helpfulSuggestions.forInsecureRegistry(), exceptionDuringBuildSteps);

      } else {
        throw new BuildStepsExecutionException(
            helpfulSuggestions.none(), executionException.getCause());
      }

    } catch (InterruptedException | IOException | CacheDirectoryCreationException ex) {
      // TODO: Add more suggestions for various build failures.
      throw new BuildStepsExecutionException(helpfulSuggestions.none(), ex);

    } catch (CacheDirectoryNotOwnedException ex) {
      String helpfulSuggestion =
          helpfulSuggestions.forCacheDirectoryNotOwned(ex.getCacheDirectory());
      CacheConfiguration applicationLayersCacheConfiguration =
          buildSteps.getBuildConfiguration().getApplicationLayersCacheConfiguration();
      if (applicationLayersCacheConfiguration != null
          && ex.getCacheDirectory()
              .equals(applicationLayersCacheConfiguration.getCacheDirectory())) {
        helpfulSuggestion = helpfulSuggestions.forCacheNeedsClean();
      }
      throw new BuildStepsExecutionException(helpfulSuggestion, ex);
    }
  }
}
