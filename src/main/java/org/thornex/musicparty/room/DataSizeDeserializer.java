package org.thornex.musicparty.room;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.util.unit.DataSize;
import java.io.IOException;

public class DataSizeDeserializer extends JsonDeserializer<DataSize> {
    @Override public DataSize deserialize(JsonParser parser,DeserializationContext context) throws IOException { return DataSize.parse(parser.getValueAsString()); }
}
