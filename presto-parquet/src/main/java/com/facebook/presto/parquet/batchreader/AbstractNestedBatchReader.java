/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.parquet.batchreader;

import com.facebook.presto.parquet.ColumnReader;
import com.facebook.presto.parquet.DataPage;
import com.facebook.presto.parquet.DictionaryPage;
import com.facebook.presto.parquet.Field;
import com.facebook.presto.parquet.RichColumnDescriptor;
import com.facebook.presto.parquet.batchreader.decoders.Decoders;
import com.facebook.presto.parquet.batchreader.decoders.DefinitionLevelDecoder;
import com.facebook.presto.parquet.batchreader.decoders.RepetitionLevelDecoder;
import com.facebook.presto.parquet.batchreader.decoders.ValuesDecoder;
import com.facebook.presto.parquet.batchreader.dictionary.Dictionaries;
import com.facebook.presto.parquet.dictionary.Dictionary;
import com.facebook.presto.parquet.reader.ColumnChunk;
import com.facebook.presto.parquet.reader.PageReader;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.parquet.Preconditions;
import org.apache.parquet.io.ParquetDecodingException;

import java.io.IOException;
import java.util.List;

import static com.facebook.presto.parquet.batchreader.decoders.Decoders.readNestedPage;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public abstract class AbstractNestedBatchReader
        implements ColumnReader
{
    protected final RichColumnDescriptor columnDescriptor;

    protected Field field;
    protected int nextBatchSize;

    private Dictionary dictionary;
    private int readOffset;
    private RepetitionLevelDecoder repetitionLevelDecoder;
    private DefinitionLevelDecoder definitionLevelDecoder;
    private ValuesDecoder valuesDecoder;
    private int remainingCountInPage;
    private PageReader pageReader;
    private int lastRL = -1;

    public AbstractNestedBatchReader(RichColumnDescriptor columnDescriptor)
    {
        checkArgument(columnDescriptor.getPath().length > 1, "expected to read a nested column");
        this.columnDescriptor = requireNonNull(columnDescriptor, "columnDescriptor is null");
    }

    @Override
    public boolean isInitialized()
    {
        return pageReader != null && field != null;
    }

    @Override
    public void init(PageReader pageReader, Field field)
    {
        Preconditions.checkState(!isInitialized(), "already initialized");
        this.pageReader = requireNonNull(pageReader, "pageReader is null");
        checkArgument(pageReader.getTotalValueCount() > 0, "page is empty");

        this.field = requireNonNull(field, "field is null");

        DictionaryPage dictionaryPage = pageReader.readDictionaryPage();
        if (dictionaryPage != null) {
            dictionary = Dictionaries.createDictionary(columnDescriptor, dictionaryPage);
        }
    }

    @Override
    public void prepareNextRead(int batchSize)
    {
        readOffset = readOffset + nextBatchSize;
        nextBatchSize = batchSize;
    }

    @Override
    public ColumnChunk readNext()
    {
        ColumnChunk columnChunk = null;
        try {
            seek();

            if (field.isRequired()) {
                columnChunk = readNestedNoNull();
            }
            else {
                columnChunk = readNestedWithNull();
            }
        }
        catch (IOException ex) {
            throw new ParquetDecodingException("Failed to decode.", ex);
        }

        readOffset = 0;
        nextBatchSize = 0;

        return columnChunk;
    }

    private void seek()
            throws IOException
    {
        if (readOffset == 0) {
            return;
        }

        skip(readOffset);
    }

    protected void readNextPage()
    {
        remainingCountInPage = 0;

        DataPage page = pageReader.readPage();
        if (page == null) {
            return;
        }

        Decoders.NestedDecoders decodersCombo = readNestedPage(page, columnDescriptor, dictionary);
        repetitionLevelDecoder = decodersCombo.getRepetitionLevelDecoder();
        definitionLevelDecoder = decodersCombo.getDefinitionLevelDecoder();
        valuesDecoder = decodersCombo.getValuesDecoder();

        remainingCountInPage = page.getValueCount();
    }

    protected abstract ColumnChunk readNestedNoNull()
            throws IOException;

    protected abstract ColumnChunk readNestedWithNull()
            throws IOException;

    protected abstract void skip(int skipSize)
            throws IOException;

    protected final RepetitionLevelDecodingInfo readRLs(int batchSize)
            throws IOException
    {
        IntList rlsList = new IntArrayList(batchSize);

        int remainingInBatch = batchSize + 1;

        RepetitionLevelDecodingInfo repetitionLevelDecodingInfo = new RepetitionLevelDecodingInfo();

        if (remainingCountInPage == 0) {
            readNextPage();
        }

        int startOffset = 0;

        if (lastRL != -1) {
            rlsList.add(lastRL);
            lastRL = -1;
            remainingInBatch--;
        }

        while (remainingInBatch > 0) {
            int read = repetitionLevelDecoder.readNext(rlsList, remainingInBatch);
            if (read == 0) {
                int endOffset = rlsList.size();
                repetitionLevelDecodingInfo.add(new DefinitionLevelValuesDecoderInfo(definitionLevelDecoder, valuesDecoder, startOffset, endOffset));
                remainingCountInPage -= (endOffset - startOffset);
                startOffset = endOffset;
                readNextPage();
                if (remainingCountInPage == 0) {
                    break;
                }
            }
            else {
                remainingInBatch -= read;
            }
        }

        if (remainingInBatch == 0) {
            lastRL = 0;
            rlsList.remove(rlsList.size() - 1);
        }

        if (repetitionLevelDecoder != null) {
            repetitionLevelDecodingInfo.add(new DefinitionLevelValuesDecoderInfo(definitionLevelDecoder, valuesDecoder, startOffset, rlsList.size()));
        }

        repetitionLevelDecodingInfo.setRepetitionLevels(rlsList.toIntArray());

        return repetitionLevelDecodingInfo;
    }

    protected final DefinitionLevelDecodingInfo readDLs(List<DefinitionLevelValuesDecoderInfo> decoderInfos, int batchSize)
            throws IOException
    {
        DefinitionLevelDecodingInfo definitionLevelDecodingInfo = new DefinitionLevelDecodingInfo();

        int[] dls = new int[batchSize];

        int remainingInBatch = batchSize;
        for (DefinitionLevelValuesDecoderInfo decoderInfo : decoderInfos) {
            int readChunkSize = decoderInfo.getEnd() - decoderInfo.getStart();
            decoderInfo.getDefinitionLevelDecoder().readNext(dls, decoderInfo.getStart(), readChunkSize);
            definitionLevelDecodingInfo.add(new ValuesDecoderInfo(decoderInfo.getValuesDecoder(), decoderInfo.getStart(), decoderInfo.getEnd()));
            remainingInBatch -= readChunkSize;
        }

        if (remainingInBatch != 0) {
            throw new IllegalStateException("We didn't read correct number of DLs");
        }

        definitionLevelDecodingInfo.setDLs(dls);

        return definitionLevelDecodingInfo;
    }
}
