package com.eza.spicyex.lyrics;

import android.content.Context;

import com.atilika.kuromoji.dict.CharacterDefinitions;
import com.atilika.kuromoji.dict.ConnectionCosts;
import com.atilika.kuromoji.dict.InsertedDictionary;
import com.atilika.kuromoji.dict.TokenInfoDictionary;
import com.atilika.kuromoji.dict.UnknownDictionary;
import com.atilika.kuromoji.trie.DoubleArrayTrie;
import com.atilika.kuromoji.unidic.Tokenizer;
import com.atilika.kuromoji.util.ResourceResolver;
import com.atilika.kuromoji.util.SimpleResourceResolver;
import java.io.InputStream;

/**
 * Builds kuromoji's unidic {@link Tokenizer} with its largest table served from a memory-mapped
 * file rather than the Java heap.
 *
 * <p>{@code TokenizerBase.Builder#loadDictionaries} is protected, so the one table that matters can
 * be swapped without touching the library or its other five loaders. See
 * {@link MappedKuromojiDictionaries} for why connectionCosts.bin specifically, and for the
 * fallback: if mapping fails for any reason this defers to kuromoji's own loader and the result is
 * exactly the build we had before.
 */
final class MappedTokenizerBuilder extends Tokenizer.Builder {
    private final Context context;

    private MappedTokenizerBuilder(Context context) {
        this.context = context;
        // Tokenizer.Builder's constructor sets resolver = new SimpleResourceResolver(getClass()),
        // and SimpleResourceResolver resolves class-relative. In a subclass that getClass() is
        // this one, so every dictionary would be looked up in our own package and nothing would
        // be found - not even by super.loadDictionaries(). Point it back at kuromoji's package.
        ResourceResolver packaged = new SimpleResourceResolver(Tokenizer.class);
        this.resolver = name -> {
            InputStream downloaded = LanguageModelPack.open("kuromoji/" + name);
            return downloaded != null ? downloaded : packaged.resolve(name);
        };
    }

    /** @return a tokenizer, mapped where possible; never null. */
    static Tokenizer build(Context context) {
        if (context == null) return new Tokenizer();
        try {
            return new MappedTokenizerBuilder(context).build();
        } catch (Throwable t) {
            return new Tokenizer();
        }
    }

    @Override
    protected void loadDictionaries() {
        // Deliberately not super.loadDictionaries(): that would allocate the 71.5MB heap copy
        // first and only then let us throw it away, which is the allocation spike this exists to
        // avoid. Every other table loads exactly as the superclass loads it.
        ConnectionCosts mapped = MappedKuromojiDictionaries.connectionCosts(context, resolver);
        if (mapped == null) {
            super.loadDictionaries();
            return;
        }
        try {
            connectionCosts = mapped;
            doubleArrayTrie = DoubleArrayTrie.newInstance(resolver);
            tokenInfoDictionary = TokenInfoDictionary.newInstance(resolver);
            characterDefinitions = CharacterDefinitions.newInstance(resolver);
            unknownDictionary = UnknownDictionary.newInstance(
                    resolver, characterDefinitions, totalFeatures);
            insertedDictionary = new InsertedDictionary(totalFeatures);
        } catch (Exception failed) {
            throw new RuntimeException("Could not load dictionaries.", failed);
        }
    }
}
