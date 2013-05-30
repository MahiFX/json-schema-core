/*
 * Copyright (c) 2013, Francis Galiegue <fgaliegue@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Lesser GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Lesser GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.fge.jsonschema.walk;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jackson.jsonpointer.JsonPointer;
import com.github.fge.jackson.jsonpointer.TokenResolver;
import com.github.fge.jsonschema.CoreMessageBundle;
import com.github.fge.jsonschema.exceptions.ExceptionProvider;
import com.github.fge.jsonschema.exceptions.InvalidSchemaException;
import com.github.fge.jsonschema.exceptions.ProcessingException;
import com.github.fge.jsonschema.exceptions.SchemaWalkingException;
import com.github.fge.jsonschema.load.RefResolver;
import com.github.fge.jsonschema.load.SchemaLoader;
import com.github.fge.jsonschema.load.configuration.LoadingConfiguration;
import com.github.fge.jsonschema.processing.Processor;
import com.github.fge.jsonschema.processing.ProcessorChain;
import com.github.fge.jsonschema.report.ProcessingMessage;
import com.github.fge.jsonschema.report.ProcessingReport;
import com.github.fge.jsonschema.syntax.SyntaxMessageBundle;
import com.github.fge.jsonschema.syntax.SyntaxProcessor;
import com.github.fge.jsonschema.tree.SchemaTree;
import com.github.fge.jsonschema.util.ValueHolder;
import com.github.fge.jsonschema.util.equivalence.SchemaTreeEquivalence;
import com.google.common.base.Equivalence;
import com.google.common.collect.Lists;

import java.util.Collections;
import java.util.List;

/**
 * A schema walker performing JSON Reference resolution
 *
 * <p>Unlike {@link SimpleSchemaWalker}, this schema walker will attempt to
 * resolve a JSON Reference when it sees one; it also performs syntax checking
 * on new trees.</p>
 *
 * <p>It also prevents information loss or infinite walking. The first event
 * can happen if a JSON Reference resolves to an immediate child of the current
 * tree (in which case all other children would be ignored), the second can
 * happen if the reference resolves to a parent of the current tree.</p>
 */
public final class ResolvingSchemaWalker
    extends SchemaWalker
{
    private static final CoreMessageBundle BUNDLE
        = CoreMessageBundle.getInstance();

    private static final ProcessingMessage MESSAGE = new ProcessingMessage()
        .message(SyntaxMessageBundle.get().getKey("core.invalidSchema"))
        .setExceptionProvider(new ExceptionProvider()
        {
            @Override
            public ProcessingException doException(
                final ProcessingMessage message)
            {
                return new InvalidSchemaException(message);
            }
        });

    private static final Equivalence<SchemaTree> EQUIVALENCE
        = SchemaTreeEquivalence.getInstance();

    private final Processor<ValueHolder<SchemaTree>, ValueHolder<SchemaTree>>
        processor;

    public ResolvingSchemaWalker(final SchemaTree tree,
        final SchemaWalkingConfiguration cfg)
    {
        super(tree, cfg);
        final LoadingConfiguration loadingCfg = cfg.loadingCfg;
        final SchemaLoader loader = new SchemaLoader(loadingCfg);
        final RefResolver refResolver = new RefResolver(loader);
        final SyntaxProcessor syntaxProcessor
            = new SyntaxProcessor(cfg.bundle, cfg.checkers);
        processor = ProcessorChain.startWith(refResolver)
            .chainWith(syntaxProcessor).failOnError(MESSAGE).getProcessor();
    }

    public ResolvingSchemaWalker(final SchemaTree tree)
    {
        this(tree, SchemaWalkingConfiguration.byDefault());
    }

    @Override
    public <T> void resolveTree(final SchemaListener<T> listener,
        final ProcessingReport report)
        throws ProcessingException
    {
        final SchemaTree newTree = processor.process(report,
            ValueHolder.hold("schema", tree)).getValue();
        if (EQUIVALENCE.equivalent(tree, newTree))
            return;
        checkTrees(tree, newTree);
        listener.onTreeChange(tree, newTree);
        tree = newTree;
    }

    @Override
    public String toString()
    {
        return "recursive tree walker ($ref resolution)";
    }

    private static void checkTrees(final SchemaTree tree,
        final SchemaTree newTree)
        throws ProcessingException
    {
        /*
         * We can rely on URIs here: at worst the starting URI was empty, but if
         * we actually fetched another schema, it will never be the empty URI. A
         * simple equality check on URIs can immediately tell us whether the
         * schema is the same.
         */
        if (!tree.getLoadingRef().equals(newTree.getLoadingRef()))
            return;
        /*
         * If it is, we just need to check that their pointers are disjoint. If
         * they are not, it means one is a prefix for the other one. Test this
         * by collecting the two trees' token resolvers and see if they share a
         * common subset at index 0.
         *
         * Note that the pointer can not be equal, of course: this would have
         * been caught by the ref resolver.
         */
        final JsonPointer sourcePointer = tree.getPointer();
        final JsonPointer targetPointer = newTree.getPointer();

        final List<TokenResolver<JsonNode>> sourceTokens
            = Lists.newArrayList(sourcePointer);
        final List<TokenResolver<JsonNode>> targetTokens
            = Lists.newArrayList(targetPointer);

        final ProcessingMessage message = new ProcessingMessage().message("")
            .put("schemaURI", tree.getLoadingRef())
            .put("source", sourcePointer.toString())
            .put("target", targetPointer.toString());


        /*
         * Check if there is an attempt to expand to a parent tree
         */
        if (Collections.indexOfSubList(sourceTokens, targetTokens) == 0)
            throw new SchemaWalkingException(message
                .message(BUNDLE.getKey("schemaWalking.parentExpand")));
        /*
         * Check if there is an attempt to expand to a subtree
         */
        if (Collections.indexOfSubList(targetTokens, sourceTokens) == 0)
            throw new SchemaWalkingException(message
                .message(BUNDLE.getKey("schemaWalking.subtreeExpand")));
    }
}