/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.percolator;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.core.BinaryFieldMapper;
import org.elasticsearch.index.mapper.core.KeywordFieldMapper;
import org.elasticsearch.index.mapper.core.StringFieldMapper;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.index.query.QueryShardContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class PercolatorFieldMapper extends FieldMapper {

    public static final String TYPE_NAME = ".percolator";
    public static final String NAME = "query";
    public static final String CONTENT_TYPE = "percolator";
    public static final PercolatorFieldType FIELD_TYPE = new PercolatorFieldType();

    private static final String EXTRACTED_TERMS_FIELD_NAME = "extracted_terms";
    private static final String UNKNOWN_QUERY_FIELD_NAME = "unknown_query";
    private static final String QUERY_BUILDER_FIELD_NAME = "query_builder_field";

    public static final String EXTRACTED_TERMS_FULL_FIELD_NAME = NAME + "." + EXTRACTED_TERMS_FIELD_NAME;
    public static final String UNKNOWN_QUERY_FULL_FIELD_NAME = NAME + "." + UNKNOWN_QUERY_FIELD_NAME;
    public static final String QUERY_BUILDER_FULL_FIELD_NAME = NAME + "." + QUERY_BUILDER_FIELD_NAME;

    public static class Builder extends FieldMapper.Builder<Builder, PercolatorFieldMapper> {

        private final QueryShardContext queryShardContext;

        public Builder(QueryShardContext queryShardContext) {
            super(NAME, FIELD_TYPE, FIELD_TYPE);
            this.queryShardContext = queryShardContext;
        }

        @Override
        public PercolatorFieldMapper build(BuilderContext context) {
            context.path().add(name);
            KeywordFieldMapper extractedTermsField = createExtractQueryFieldBuilder(EXTRACTED_TERMS_FIELD_NAME).build(context);
            KeywordFieldMapper unknownQueryField = createExtractQueryFieldBuilder(UNKNOWN_QUERY_FIELD_NAME).build(context);
            BinaryFieldMapper queryBuilderField = createQueryBuilderFieldBuilder().build(context);
            context.path().remove();
            return new PercolatorFieldMapper(name(), fieldType, defaultFieldType, context.indexSettings(), multiFieldsBuilder.build(this, context), copyTo, queryShardContext, extractedTermsField, unknownQueryField, queryBuilderField);
        }

        static KeywordFieldMapper.Builder createExtractQueryFieldBuilder(String name) {
            KeywordFieldMapper.Builder queryMetaDataFieldBuilder = new KeywordFieldMapper.Builder(name);
            queryMetaDataFieldBuilder.docValues(false);
            queryMetaDataFieldBuilder.store(false);
            queryMetaDataFieldBuilder.indexOptions(IndexOptions.DOCS);
            return queryMetaDataFieldBuilder;
        }

        static BinaryFieldMapper.Builder createQueryBuilderFieldBuilder() {
            BinaryFieldMapper.Builder builder = new BinaryFieldMapper.Builder(QUERY_BUILDER_FIELD_NAME);
            builder.docValues(true);
            builder.indexOptions(IndexOptions.NONE);
            builder.store(false);
            builder.fieldType().setDocValuesType(DocValuesType.BINARY);
            return builder;
        }
    }

    public static class TypeParser implements FieldMapper.TypeParser {

        @Override
        public Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            return new Builder(parserContext.queryShardContext());
        }
    }

    public static final class PercolatorFieldType extends MappedFieldType {

        public PercolatorFieldType() {
            setName(NAME);
            setIndexOptions(IndexOptions.NONE);
            setDocValuesType(DocValuesType.NONE);
            setStored(false);
        }

        public PercolatorFieldType(MappedFieldType ref) {
            super(ref);
        }

        @Override
        public MappedFieldType clone() {
            return new PercolatorFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }
    }

    private final boolean mapUnmappedFieldAsString;
    private final QueryShardContext queryShardContext;
    private final KeywordFieldMapper queryTermsField;
    private final KeywordFieldMapper unknownQueryField;
    private final BinaryFieldMapper queryBuilderField;

    public PercolatorFieldMapper(String simpleName, MappedFieldType fieldType, MappedFieldType defaultFieldType,
                                 Settings indexSettings, MultiFields multiFields, CopyTo copyTo, QueryShardContext queryShardContext,
                                 KeywordFieldMapper queryTermsField, KeywordFieldMapper unknownQueryField,
                                 BinaryFieldMapper queryBuilderField) {
        super(simpleName, fieldType, defaultFieldType, indexSettings, multiFields, copyTo);
        this.queryShardContext = queryShardContext;
        this.queryTermsField = queryTermsField;
        this.unknownQueryField = unknownQueryField;
        this.queryBuilderField = queryBuilderField;
        this.mapUnmappedFieldAsString = PercolatorQueryCache.INDEX_MAP_UNMAPPED_FIELDS_AS_STRING_SETTING.get(indexSettings);
    }

    @Override
    public Mapper parse(ParseContext context) throws IOException {
        QueryShardContext queryShardContext = new QueryShardContext(this.queryShardContext);
        QueryBuilder<?> queryBuilder = parseQueryBuilder(queryShardContext.parseContext(), context.parser());
        // Fetching of terms, shapes and indexed scripts happen during this rewrite:
        queryBuilder = queryBuilder.rewrite(queryShardContext);

        try (XContentBuilder builder = XContentFactory.contentBuilder(PercolatorQueryCache.QUERY_BUILDER_CONTENT_TYPE)) {
            queryBuilder.toXContent(builder, new MapParams(Collections.emptyMap()));
            builder.flush();
            byte[] queryBuilderAsBytes = builder.bytes().toBytes();
            context.doc().add(new Field(queryBuilderField.name(), queryBuilderAsBytes, queryBuilderField.fieldType()));
        }

        Query query = toQuery(queryShardContext, mapUnmappedFieldAsString, queryBuilder);
        ExtractQueryTermsService.extractQueryTerms(query, context.doc(), queryTermsField.name(), unknownQueryField.name(), queryTermsField.fieldType());
        return null;
    }

    public static Query parseQuery(QueryShardContext context, boolean mapUnmappedFieldsAsString, XContentParser parser) throws IOException {
        return toQuery(context, mapUnmappedFieldsAsString, parseQueryBuilder(context.parseContext(), parser));
    }

    static Query toQuery(QueryShardContext context, boolean mapUnmappedFieldsAsString, QueryBuilder<?> queryBuilder) throws IOException {
        context.reset();
        // This means that fields in the query need to exist in the mapping prior to registering this query
        // The reason that this is required, is that if a field doesn't exist then the query assumes defaults, which may be undesired.
        //
        // Even worse when fields mentioned in percolator queries do go added to map after the queries have been registered
        // then the percolator queries don't work as expected any more.
        //
        // Query parsing can't introduce new fields in mappings (which happens when registering a percolator query),
        // because field type can't be inferred from queries (like document do) so the best option here is to disallow
        // the usage of unmapped fields in percolator queries to avoid unexpected behaviour
        //
        // if index.percolator.map_unmapped_fields_as_string is set to true, query can contain unmapped fields which will be mapped
        // as an analyzed string.
        context.setAllowUnmappedFields(false);
        context.setMapUnmappedFieldAsString(mapUnmappedFieldsAsString);
        context.parseFieldMatcher(context.getIndexSettings().getParseFieldMatcher());
        try {
            return queryBuilder.toQuery(context);
        } finally {
            context.reset();
        }
    }

    static QueryBuilder<?> parseQueryBuilder(QueryParseContext context, XContentParser parser) {
        context.reset(parser);
        try {
            return context.parseInnerQueryBuilder();
        } catch (IOException e) {
            throw new ParsingException(parser.getTokenLocation(), "Failed to parse", e);
        } finally {
            context.reset(null);
        }
    }

    @Override
    public Iterator<Mapper> iterator() {
        return Arrays.<Mapper>asList(queryTermsField, unknownQueryField, queryBuilderField).iterator();
    }

    @Override
    protected void parseCreateField(ParseContext context, List<Field> fields) throws IOException {
        throw new UnsupportedOperationException("should not be invoked");
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

}
