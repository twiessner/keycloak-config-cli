/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2021 adorsys GmbH & Co. KG @ https://adorsys.com
 * ---
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
 * ---license-end
 */

package de.adorsys.keycloak.config.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import de.adorsys.keycloak.config.exception.InvalidImportException;
import de.adorsys.keycloak.config.model.KeycloakImport;
import de.adorsys.keycloak.config.model.RealmImport;
import de.adorsys.keycloak.config.properties.ImportConfigProperties;
import de.adorsys.keycloak.config.util.ChecksumUtil;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class KeycloakImportProvider {
    private StringSubstitutor interpolator = null;

    private final ImportConfigProperties importConfigProperties;

    public KeycloakImportProvider(
            ImportConfigProperties importConfigProperties) {
        this.importConfigProperties = importConfigProperties;

        if (importConfigProperties.isVarSubstitution()) {
            this.interpolator = StringSubstitutor.createInterpolator()
                    .setEnableSubstitutionInVariables(importConfigProperties.isVarSubstitutionInVariables())
                    .setEnableUndefinedVariableException(importConfigProperties.isVarSubstitutionUndefinedThrowsExceptions());
        }
    }

    public KeycloakImport get() {
        KeycloakImport keycloakImport;
        String importFilePath = importConfigProperties.getPath();

        try {
            UrlResource importFilePathUrl = new UrlResource(importFilePath);
            if (ResourceUtils.URL_PROTOCOL_FILE.equals(importFilePathUrl.getURL().getProtocol())) {
                keycloakImport = getFromLocal(importFilePathUrl);
            } else {
                keycloakImport = getFromRemote(importFilePathUrl);
            }

            return keycloakImport;
        } catch (IOException e) {
            throw new InvalidImportException(e);
        }
    }

    public KeycloakImport getFromLocalDirectory(File locationFile) {
        Map<String, RealmImport> realmImports = Optional.ofNullable(locationFile.listFiles())
                .map(Arrays::asList)
                .orElse(Collections.emptyList())
                .stream()
                .filter(File::isFile)
                // https://stackoverflow.com/a/52130074/8087167
                .collect(Collectors.toMap(
                        File::getName,
                        this::readRealmImport,
                        (u, v) -> {
                            throw new IllegalStateException(String.format("Duplicate key %s", u));
                        },
                        TreeMap::new
                ));

        return new KeycloakImport(realmImports);
    }

    public KeycloakImport getFromLocalFile(File importFile) {
        Map<String, RealmImport> realmImports = new HashMap<>();
        RealmImport realmImport = readRealmImport(importFile);
        realmImports.put(importFile.getName(), realmImport);

        return new KeycloakImport(realmImports);
    }

    private KeycloakImport getFromRemote(UrlResource location) throws IOException {
        String content = readURL(location.getURL());
        String fileName = location.getFilename();

        Map<String, RealmImport> realmImports = new HashMap<>();
        RealmImport realmImport = readRealmImportFromString(fileName, content);
        realmImports.put(fileName, realmImport);

        return new KeycloakImport(realmImports);
    }

    private KeycloakImport getFromLocal(UrlResource location) throws IOException {
        File locationFile = location.getFile();

        if (!locationFile.exists() || !locationFile.canRead()) {
            throw new InvalidImportException("import.path does not exists: " + locationFile.getAbsolutePath());
        }

        if (locationFile.isDirectory()) {
            return getFromLocalDirectory(locationFile);
        }

        return getFromLocalFile(locationFile);
    }

    private RealmImport readRealmImport(File importFile) {
        try {
            String importConfig = readFile(importFile);

            return readRealmImportFromString(importFile.getName(), importConfig);
        } catch (IOException e) {
            throw new InvalidImportException(e);
        }
    }

    private RealmImport readRealmImportFromString(String fileName, String importConfig) throws IOException {
        ObjectMapper objectMapper = getObjectMapper(fileName);

        if (importConfigProperties.isVarSubstitution()) {
            importConfig = interpolator.replace(importConfig);
        }

        String checksum = ChecksumUtil.checksum(importConfig.getBytes(StandardCharsets.UTF_8));

        RealmImport realmImport = objectMapper.readValue(importConfig, RealmImport.class);
        realmImport.setChecksum(checksum);

        return realmImport;
    }

    private String readFile(File importFile) throws IOException {
        byte[] importFileInBytes = Files.readAllBytes(importFile.toPath());
        return new String(importFileInBytes, StandardCharsets.UTF_8);
    }

    private String readURL(URL importUrl) throws IOException {
        URLConnection urlConnection = importUrl.openConnection();
        urlConnection.setDoOutput(true);

        // https://stackoverflow.com/a/5137446
        if (importUrl.getUserInfo() != null) {
            String basicAuth = "Basic " + new String(Base64.getEncoder().encode(importUrl.getUserInfo().getBytes()));
            urlConnection.setRequestProperty("Authorization", basicAuth);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
        return reader.lines().collect(Collectors.joining(System.lineSeparator()));
    }

    private ObjectMapper getObjectMapper(String filename) {
        ImportConfigProperties.ImportFileType fileType = importConfigProperties.getFileType();

        ObjectMapper objectMapper;

        switch (fileType) {
            case YAML:
                objectMapper = new ObjectMapper(new YAMLFactory());
                break;
            case JSON:
                objectMapper = new ObjectMapper();
                break;
            case AUTO:
                String fileExt = FilenameUtils.getExtension(filename);
                switch (fileExt) {
                    case "yaml":
                    case "yml":
                        objectMapper = new ObjectMapper(new YAMLFactory());
                        break;
                    case "json":
                        objectMapper = new ObjectMapper();
                        break;
                    default:
                        throw new InvalidImportException("Unknown file extension: " + fileExt);
                }
                break;
            default:
                throw new InvalidImportException("Unknown import file type: " + fileType.toString());
        }

        objectMapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return objectMapper;
    }
}
