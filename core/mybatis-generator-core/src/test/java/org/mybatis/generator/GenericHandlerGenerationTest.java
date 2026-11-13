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
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.generator.api.GeneratedJavaFile;
import org.mybatis.generator.api.Indenter;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.api.PluginAdapter;
import org.mybatis.generator.api.dom.DefaultJavaFormatter;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.config.xml.ConfigurationParser;

public class GenericHandlerGenerationTest {
    @Test
    void automaticallyCarriesExplicitGenericTypesWithAndWithoutHandlers() throws Exception {
        var files = generate("""
                <columnOverride column="PAYLOAD"
                    javaType="java.util.Map&lt;java.lang.String,java.util.List&lt;java.lang.Integer&gt;&gt;"
                    typeHandler="example.JsonHandler"/>
                <columnOverride column="NAMES" javaType="java.util.List&lt;java.lang.String&gt;"/>
                <columnOverride column="STATE" javaType="java.lang.String" typeHandler="example.JsonHandler"/>
                """, "");
        var support = supportSource(files);
        assertThat(support).contains(
                "new org.apache.ibatis.type.TypeReference<Map<String, List<Integer>>>() {}.getRawType()",
                "new org.apache.ibatis.type.TypeReference<List<String>>() {}.getRawType()",
                ".withJavaType(String.class)");
        assertThat(support).doesNotContain("List<String>.class", "Map<String, List<Integer>>.class");
        assertThat(files).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource({
            "false,false,false", "false,true,false", "true,false,false", "true,true,false",
            "false,false,true", "false,true,true", "true,false,true", "true,true,true"
    })
    void emitsTypeOnlyForExplicitJavaTypeOrForce(boolean explicitType, boolean handler, boolean force)
            throws Exception {
        var override = """
                <columnOverride column="STATE"%s%s>
                  <property name="forceJavaTypeIntoMapping" value="%s"/>
                </columnOverride>
                """.formatted(explicitType ? " javaType=\"java.lang.Long\"" : "",
                        handler ? " typeHandler=\"example.JsonHandler\"" : "", force);
        var support = supportSource(generate(override, ""));
        var state = support.lines().filter(line -> line.contains("column(\"STATE\"")).findFirst().orElseThrow();
        if (explicitType || force) {
            assertThat(state).contains(".withJavaType(" + (explicitType ? "Long" : "String") + ".class)");
        } else {
            assertThat(state).doesNotContain(".withJavaType(");
        }
        assertThat(state.contains("example.JsonHandler")).isEqualTo(handler);
        assertThat(support).doesNotContain("org.apache.ibatis.type.TypeReference");
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void carriesPluginInferredGenericTypeOnlyWhenForced(boolean handler, boolean force) throws Exception {
        var override = """
                <columnOverride column="NAMES"%s>
                  <property name="forceJavaTypeIntoMapping" value="%s"/>
                </columnOverride>
                """.formatted(handler ? " typeHandler=\"example.JsonHandler\"" : "", force);
        var files = generate(override, "<plugin type=\"" + InferredGenericTypePlugin.class.getName() + "\"/>");
        var support = supportSource(files);
        if (force) {
            assertThat(support).contains("new org.apache.ibatis.type.TypeReference<List<String>>() {}.getRawType()");
        } else {
            assertThat(support).doesNotContain("org.apache.ibatis.type.TypeReference", ".withJavaType(");
        }
        assertThat(support.contains("example.JsonHandler")).isEqualTo(handler);
    }

    @Test
    void leavesDefaultColumnsWithoutJavaTypeMetadata() throws Exception {
        var support = supportSource(generate("", ""));
        assertThat(support).doesNotContain(".withJavaType(", "org.apache.ibatis.type.TypeReference");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "int | Integer.class",
            "java.lang.String[] | String[].class",
            "java.util.List<java.lang.String> | "
                    + "new org.apache.ibatis.type.TypeReference<List<String>>() {}.getRawType()",
            "java.util.List<? extends java.lang.Number> | "
                    + "new org.apache.ibatis.type.TypeReference<List<? extends Number>>() {}.getRawType()",
            "java.util.List<? super java.lang.Integer> | "
                    + "new org.apache.ibatis.type.TypeReference<List<? super Integer>>() {}.getRawType()"
    })
    void emitsValidTypeExpressions(String javaType, String expression) throws Exception {
        var override = """
                <columnOverride column="PAYLOAD" javaType="%s"/>
                """.formatted(javaType.replace("<", "&lt;").replace(">", "&gt;"));
        var support = supportSource(generate(override, ""));
        assertThat(support).contains(".withJavaType(" + expression + ")");
    }

    @Test
    void avoidsTypeReferenceNameCollisionsWithApplicationTypes() throws Exception {
        var files = generate("""
                <columnOverride column="PAYLOAD"
                    javaType="java.util.List&lt;org.mybatis.generator.GenericHandlerGenerationTest.TypeReference&gt;"/>
                """, "");
        var support = supportSource(files);
        assertThat(support).contains("import org.mybatis.generator.GenericHandlerGenerationTest.TypeReference;",
                ".withJavaType(new org.apache.ibatis.type.TypeReference<List<TypeReference>>() {}.getRawType())");
        assertThat(support).doesNotContain("import org.apache.ibatis.type.TypeReference;");
    }

    private List<GeneratedJavaFile> generate(String overrides, String plugins) throws Exception {
        var url = "jdbc:hsqldb:mem:generic_" + UUID.randomUUID() + ";shutdown=true";
        try (var connection = DriverManager.getConnection(url, "SA", "");
                var statement = connection.createStatement()) {
            statement.execute("""
                    create table sample (
                        id integer primary key,
                        payload varchar(200),
                        names varchar(200),
                        state varchar(20)
                    )
                    """);
            var xml = """
                    <?xml version="1.0"?>
                    <!DOCTYPE generatorConfiguration PUBLIC
                        "-//mybatis.org//DTD MyBatis Generator Configuration 1.0//EN"
                        "https://mybatis.org/dtd/mybatis-generator-config_1_0.dtd">
                    <generatorConfiguration><context id="generic">
                      %s
                      <jdbcConnection driverClass="org.hsqldb.jdbc.JDBCDriver"
                          connectionURL="%s" userId="SA" password=""/>
                      <modelGenerator targetPackage="sample" targetProject="target"/>
                      <clientGenerator targetPackage="sample" targetProject="target"/>
                      <table tableName="SAMPLE">%s</table>
                    </context></generatorConfiguration>
                    """.formatted(plugins, url, overrides);
            var configuration = new ConfigurationParser().parseConfiguration(new StringReader(xml));
            var generator = new MyBatisGenerator.Builder().withConfiguration(configuration).build();
            assertThat(generator.generateOnly()).isEmpty();
            return generator.getGeneratedJavaFiles();
        }
    }

    private String supportSource(List<GeneratedJavaFile> files) {
        var formatter = new DefaultJavaFormatter();
        formatter.setIndenter(Indenter.defaultIndenter());
        var supportFiles = files.stream()
                .filter(file -> file.getFileName().endsWith("DynamicSqlSupport.java")).toList();
        assertThat(supportFiles).hasSize(1);
        return formatter.getFormattedContent(supportFiles.get(0).getCompilationUnit());
    }

    public static class TypeReference {
    }

    public static class InferredGenericTypePlugin extends PluginAdapter {
        @Override
        public boolean validate(List<String> warnings) {
            return true;
        }

        @Override
        public void initialized(IntrospectedTable table) {
            table.getColumn("NAMES").orElseThrow().setFullyQualifiedJavaType(
                    new FullyQualifiedJavaType("java.util.List<java.lang.String>"));
        }
    }
}
