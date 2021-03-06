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

import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.block.IntArrayBlock;
import com.facebook.presto.common.block.RunLengthEncodedBlock;
import com.facebook.presto.parquet.RichColumnDescriptor;
import com.facebook.presto.parquet.batchreader.decoders.ValuesDecoder.Int32ValuesDecoder;
import com.facebook.presto.parquet.reader.ColumnChunk;

import java.io.IOException;
import java.util.Optional;

public class Int32NestedBatchReader
        extends AbstractNestedBatchReader
{
    public Int32NestedBatchReader(RichColumnDescriptor columnDescriptor)
    {
        super(columnDescriptor);
    }

    @Override
    protected ColumnChunk readNestedWithNull()
            throws IOException
    {
        int maxDL = columnDescriptor.getMaxDefinitionLevel();
        RepetitionLevelDecodingInfo repetitionLevelDecodingInfo = readRLs(nextBatchSize);

        DefinitionLevelDecodingInfo definitionLevelDecodingInfo = readDLs(repetitionLevelDecodingInfo.getDLValuesDecoderInfos(), repetitionLevelDecodingInfo.getRepetitionLevels().length);

        int[] dls = definitionLevelDecodingInfo.getDLs();
        int newBatchSize = 0;
        int batchNonNullCount = 0;
        for (ValuesDecoderInfo valuesDecoderInfo : definitionLevelDecodingInfo.getValuesDecoderInfos()) {
            int nonNullCount = 0;
            int valueCount = 0;
            for (int i = valuesDecoderInfo.getStart(); i < valuesDecoderInfo.getEnd(); i++) {
                nonNullCount += (dls[i] == maxDL ? 1 : 0);
                valueCount += (dls[i] >= maxDL - 1 ? 1 : 0);
            }
            batchNonNullCount += nonNullCount;
            newBatchSize += valueCount;
            valuesDecoderInfo.setNonNullCount(nonNullCount);
            valuesDecoderInfo.setValueCount(valueCount);
        }

        if (batchNonNullCount == 0) {
            Block block = RunLengthEncodedBlock.create(field.getType(), null, newBatchSize);
            return new ColumnChunk(block, dls, repetitionLevelDecodingInfo.getRepetitionLevels());
        }

        int[] values = new int[newBatchSize];
        boolean[] isNull = new boolean[newBatchSize];

        int offset = 0;
        for (ValuesDecoderInfo valuesDecoderInfo : definitionLevelDecodingInfo.getValuesDecoderInfos()) {
            ((Int32ValuesDecoder) valuesDecoderInfo.getValuesDecoder()).readNext(values, offset, valuesDecoderInfo.getNonNullCount());

            int valueDestIdx = offset + valuesDecoderInfo.getValueCount() - 1;
            int valueSrcIdx = offset + valuesDecoderInfo.getNonNullCount() - 1;
            int dlIdx = valuesDecoderInfo.getEnd() - 1;

            while (valueDestIdx >= offset) {
                if (dls[dlIdx] == maxDL) {
                    values[valueDestIdx--] = values[valueSrcIdx--];
                }
                else if (dls[dlIdx] == maxDL - 1) {
                    values[valueDestIdx] = 0;
                    isNull[valueDestIdx] = true;
                    valueDestIdx--;
                }
                dlIdx--;
            }

            offset += valuesDecoderInfo.getValueCount();
        }

        boolean hasNoNull = batchNonNullCount == newBatchSize;
        Block block = new IntArrayBlock(newBatchSize, hasNoNull ? Optional.empty() : Optional.of(isNull), values);
        return new ColumnChunk(block, dls, repetitionLevelDecodingInfo.getRepetitionLevels());
    }

    @Override
    protected ColumnChunk readNestedNoNull()
            throws IOException
    {
        int maxDL = columnDescriptor.getMaxDefinitionLevel();
        RepetitionLevelDecodingInfo repetitionLevelDecodingInfo = readRLs(nextBatchSize);

        DefinitionLevelDecodingInfo definitionLevelDecodingInfo = readDLs(repetitionLevelDecodingInfo.getDLValuesDecoderInfos(), repetitionLevelDecodingInfo.getRepetitionLevels().length);

        int[] dls = definitionLevelDecodingInfo.getDLs();
        int newBatchSize = 0;
        for (ValuesDecoderInfo valuesDecoderInfo : definitionLevelDecodingInfo.getValuesDecoderInfos()) {
            int valueCount = 0;
            for (int i = valuesDecoderInfo.getStart(); i < valuesDecoderInfo.getEnd(); i++) {
                valueCount += (dls[i] == maxDL ? 1 : 0);
            }
            newBatchSize += valueCount;
            valuesDecoderInfo.setNonNullCount(valueCount);
            valuesDecoderInfo.setValueCount(valueCount);
        }

        int[] values = new int[newBatchSize];

        int offset = 0;

        for (ValuesDecoderInfo valuesDecoderInfo : definitionLevelDecodingInfo.getValuesDecoderInfos()) {
            ((Int32ValuesDecoder) valuesDecoderInfo.getValuesDecoder()).readNext(values, offset, valuesDecoderInfo.getNonNullCount());
            offset += valuesDecoderInfo.getValueCount();
        }

        Block block = new IntArrayBlock(newBatchSize, Optional.empty(), values);
        return new ColumnChunk(block, dls, repetitionLevelDecodingInfo.getRepetitionLevels());
    }

    @Override
    protected void skip(int skipSize)
            throws IOException
    {
        int maxDL = columnDescriptor.getMaxDefinitionLevel();
        RepetitionLevelDecodingInfo repetitionLevelDecodingInfo = readRLs(skipSize);
        DefinitionLevelDecodingInfo definitionLevelDecodingInfo = readDLs(repetitionLevelDecodingInfo.getDLValuesDecoderInfos(), repetitionLevelDecodingInfo.getRepetitionLevels().length);

        int[] dls = definitionLevelDecodingInfo.getDLs();
        for (ValuesDecoderInfo valuesDecoderInfo : definitionLevelDecodingInfo.getValuesDecoderInfos()) {
            int valueCount = 0;
            for (int i = valuesDecoderInfo.getStart(); i < valuesDecoderInfo.getEnd(); i++) {
                valueCount += (dls[i] == maxDL ? 1 : 0);
            }
            Int32ValuesDecoder intValuesDecoder = (Int32ValuesDecoder) valuesDecoderInfo.getValuesDecoder();
            intValuesDecoder.skip(valueCount);
        }
    }
}
