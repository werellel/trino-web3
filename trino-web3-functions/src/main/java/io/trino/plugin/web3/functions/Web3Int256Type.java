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
package io.trino.plugin.web3.functions;

import io.airlift.slice.Slice;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.VariableWidthBlockBuilder;
import io.trino.spi.block.VariableWidthBlock;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.type.AbstractVariableWidthType;
import io.trino.spi.type.TypeOperatorDeclaration;
import io.trino.spi.type.TypeSignature;

import static io.trino.spi.type.TypeOperatorDeclaration.extractOperatorDeclaration;
import static java.lang.invoke.MethodHandles.lookup;

public abstract class Web3Int256Type
        extends AbstractVariableWidthType
{
    private final boolean signed;
    private final TypeOperatorDeclaration operatorDeclaration;

    protected Web3Int256Type(String name, boolean signed, Class<?> operatorClass)
    {
        super(new TypeSignature(name), Slice.class);
        this.signed = signed;
        this.operatorDeclaration = TypeOperatorDeclaration.builder(Slice.class)
                .addOperators(DEFAULT_READ_OPERATORS)
                .addOperators(DEFAULT_COMPARABLE_OPERATORS)
                .addOperators(extractOperatorDeclaration(operatorClass, lookup(), Slice.class))
                .build();
    }

    @Override
    public boolean isComparable()
    {
        return true;
    }

    @Override
    public boolean isOrderable()
    {
        return true;
    }

    @Override
    public TypeOperatorDeclaration getTypeOperatorDeclaration(io.trino.spi.type.TypeOperators typeOperators)
    {
        return operatorDeclaration;
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }
        return (signed ? Web3IntegerCodec.decodeSigned(getSlice(block, position)) : Web3IntegerCodec.decodeUnsigned(getSlice(block, position))).toString();
    }

    @Override
    public Slice getSlice(Block block, int position)
    {
        VariableWidthBlock valueBlock = (VariableWidthBlock) block.getUnderlyingValueBlock();
        return valueBlock.getSlice(block.getUnderlyingValuePosition(position));
    }

    @Override
    public void writeSlice(BlockBuilder blockBuilder, Slice value)
    {
        if (value.length() != Web3IntegerCodec.BYTES) {
            throw new IllegalArgumentException("canonical integer value must be exactly 32 bytes");
        }
        ((VariableWidthBlockBuilder) blockBuilder).writeEntry(value, 0, value.length());
    }

    @Override
    public void writeSlice(BlockBuilder blockBuilder, Slice value, int offset, int length)
    {
        if (length != Web3IntegerCodec.BYTES) {
            throw new IllegalArgumentException("canonical integer value must be exactly 32 bytes");
        }
        ((VariableWidthBlockBuilder) blockBuilder).writeEntry(value, offset, length);
    }

}
