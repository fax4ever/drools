/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.kie.utll.xml;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

/**
 * TODO Remove when java.time converters are provided by XStream out of the box.
 *
 * @see <a href="https://github.com/x-stream/xstream/issues/75">XStream#75</a>
 */
public class LocalDateTimeXStreamConverter implements Converter {

    private final DateTimeFormatter formatter;

    public LocalDateTimeXStreamConverter() {
        formatter = new DateTimeFormatterBuilder()
                .appendPattern( "uuuu-MM-dd'T'HH:mm:ss" )
                .appendFraction( ChronoField.NANO_OF_SECOND, 0, 9, true )
                .toFormatter();
    }

    @Override
    public void marshal( Object localDateTimeObject, HierarchicalStreamWriter writer, MarshallingContext context ) {
        LocalDateTime localDateTime = (LocalDateTime) localDateTimeObject;
        writer.setValue( formatter.format( localDateTime ) );
    }

    @Override
    public Object unmarshal( HierarchicalStreamReader reader, UnmarshallingContext context ) {
        String localDateTimeString = reader.getValue();
        try {
            return LocalDateTime.from( formatter.parse( localDateTimeString ) );
        } catch ( DateTimeException e ) {
            throw new IllegalStateException( "Failed to convert string (" + localDateTimeString + ") to type ("
                    + LocalDateTime.class.getName() + ")." );
        }
    }

    @Override
    public boolean canConvert( Class type ) {
        return LocalDateTime.class.isAssignableFrom( type );
    }

}
