package io.github.davifmn.ledger.sepa;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A small streaming reader for ISO 20022 messages (pain.001, pacs.008, camt.053).
 *
 * <p>It turns a message into "records" (one per repeated element, such as each
 * {@code CdtTrfTxInf} or {@code Ntry}) of path → text values, ignoring namespaces, so the
 * same code reads every schema version. Values outside the records (group header,
 * payment information, statement) are kept as context.
 *
 * <p>Security: DTDs are refused and external entities are off, so XXE and entity
 * expansion attacks fail; messages over 1 MB or with too many records are refused.
 */
public final class IsoXml {

    public static final int MAX_BYTES = 1 << 20;
    public static final int MAX_RECORDS = 10_000;

    private IsoXml() {
    }

    /** One repeated element: its own values by relative path, plus the values seen before it. */
    public record Record(Map<String, String> values, Map<String, String> context) {

        /** A value inside the record, by relative path ("PmtId/EndToEndId", "Amt/InstdAmt@Ccy"). */
        public String get(String path) {
            return values.get(path);
        }

        /** A value outside the record that came before it, by path suffix ("PmtInf/DbtrAcct/Id/IBAN"). */
        public String context(String suffix) {
            return bySuffix(context, suffix);
        }
    }

    public record Document(String messageType, Map<String, String> header, List<Record> records) {

        public String header(String suffix) {
            return bySuffix(header, suffix);
        }
    }

    public static Document parse(byte[] xml, String recordElement) {
        if (xml == null || xml.length == 0) {
            throw new XmlFormatException("empty message");
        }
        if (xml.length > MAX_BYTES) {
            throw new XmlFormatException("message is larger than 1 MB");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);

        Deque<String> path = new ArrayDeque<>();
        Map<String, String> scope = new LinkedHashMap<>();
        List<Record> records = new ArrayList<>();
        Map<String, String> current = null;
        Map<String, String> currentContext = null;
        int recordDepth = -1;
        String messageType = null;
        StringBuilder text = new StringBuilder();

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.DTD -> throw new XmlFormatException("DTDs are not allowed");
                    case XMLStreamConstants.ENTITY_REFERENCE -> throw new XmlFormatException("entities are not allowed");
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = reader.getLocalName();
                        path.addLast(name);
                        text.setLength(0);
                        if (path.size() == 2) {
                            messageType = name;
                        }
                        String full = String.join("/", path);
                        if (current == null && name.equals(recordElement)) {
                            if (records.size() == MAX_RECORDS) {
                                throw new XmlFormatException("more than " + MAX_RECORDS + " " + recordElement + " elements");
                            }
                            current = new LinkedHashMap<>();
                            currentContext = Map.copyOf(scope);
                            recordDepth = path.size();
                        } else if (current == null) {
                            // a repeated element outside the records (a second PmtInf, say) replaces the first one's values
                            scope.keySet().removeIf(key -> key.startsWith(full + "/") || key.startsWith(full + "@"));
                        }
                        String key = current == null ? full : relative(path, recordDepth);
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            Map<String, String> target = current == null ? scope : current;
                            target.put(key + "@" + reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.getText());
                    case XMLStreamConstants.END_ELEMENT -> {
                        String value = text.toString().strip();
                        text.setLength(0);
                        if (current != null && path.size() == recordDepth) {
                            records.add(new Record(Map.copyOf(current), currentContext));
                            current = null;
                            recordDepth = -1;
                        } else if (!value.isEmpty()) {
                            if (current != null) {
                                current.put(relative(path, recordDepth), value);
                            } else {
                                scope.put(String.join("/", path), value);
                            }
                        }
                        path.removeLast();
                    }
                    default -> {
                        // comments, whitespace, processing instructions
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new XmlFormatException("malformed XML: " + e.getMessage());
        }
        if (messageType == null) {
            throw new XmlFormatException("not an ISO 20022 Document");
        }
        return new Document(messageType, Map.copyOf(scope), List.copyOf(records));
    }

    private static String relative(Deque<String> path, int recordDepth) {
        return String.join("/", path.stream().skip(recordDepth).toList());
    }

    private static String bySuffix(Map<String, String> values, String suffix) {
        for (var entry : values.entrySet()) {
            if (entry.getKey().equals(suffix) || entry.getKey().endsWith("/" + suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
