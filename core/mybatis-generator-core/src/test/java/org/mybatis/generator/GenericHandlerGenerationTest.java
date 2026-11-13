/*
 *    Copyright 2006-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.sql.DriverManager;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.config.xml.ConfigurationParser;
import org.mybatis.generator.internal.DefaultShellCallback;

class GenericHandlerGenerationTest {
    @Test
    void automaticallyCarriesTypesForConfiguredHandlers() throws Exception {
        String url = "jdbc:hsqldb:mem:generic_generation";
        try (var connection = DriverManager.getConnection(url, "SA", "")) {
            connection.createStatement().execute("create table sample (id integer primary key, payload varchar(200), names varchar(200), state varchar(20))");
        }
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE generatorConfiguration PUBLIC \"-//mybatis.org//DTD MyBatis Generator Configuration 1.0//EN\" \"https://mybatis.org/dtd/mybatis-generator-config_1_0.dtd\">"
            + "<generatorConfiguration><context id=\"generic\">"
            + "<jdbcConnection driverClass=\"org.hsqldb.jdbc.JDBCDriver\" connectionURL=\"" + url + "\" userId=\"SA\" password=\"\"/>"
            + "<modelGenerator targetPackage=\"sample\" targetProject=\"target\"/>"
            + "<clientGenerator targetPackage=\"sample\" targetProject=\"target\"/>"
            + "<table tableName=\"SAMPLE\">"
            + "<columnOverride column=\"PAYLOAD\" javaType=\"java.util.Map&lt;java.lang.String,java.util.List&lt;java.lang.Integer&gt;&gt;\" typeHandler=\"example.JsonHandler\"/>"
            + "<columnOverride column=\"NAMES\" javaType=\"java.util.List&lt;java.lang.String&gt;\" typeHandler=\"example.JsonHandler\"/>"
            + "<columnOverride column=\"STATE\" javaType=\"java.lang.String\" typeHandler=\"example.JsonHandler\"/>"
            + "</table></context></generatorConfiguration>";
        var configuration = new ConfigurationParser().parseConfiguration(new StringReader(xml));
        var generator = new MyBatisGenerator.Builder().withConfiguration(configuration)
                .withShellCallback(new DefaultShellCallback()).withOverwriteEnabled(true).build();
        generator.generateOnly();
        var formatter = new org.mybatis.generator.api.dom.DefaultJavaFormatter();
        formatter.setIndenter(new org.mybatis.generator.api.Indenter.Builder().build());
        String support = generator.getGeneratedJavaFiles().stream()
                .filter(file -> file.getFileName().endsWith("DynamicSqlSupport.java"))
                .map(file -> formatter.getFormattedContent(file.getCompilationUnit())).collect(Collectors.joining());
        assertThat(support).contains("new TypeReference<Map<String, List<Integer>>>() {}.getRawType()",
                "new TypeReference<List<String>>() {}.getRawType()", ".withJavaType(String.class)");
        assertThat(support).doesNotContain("List<String>.class", "Map<String, List<Integer>>.class");
        assertThat(generator.getGeneratedJavaFiles()).hasSize(3);
    }
}
