package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "desc_specifiers",
        propOrder = {"specifier"}
)
public class RADescSpecifiers {
    @XmlElement(
            required = true
    )
    protected List<Specifier> specifier;

    public RADescSpecifiers() {
    }

    public List<RADescSpecifiers.Specifier> getSpecifier() {
        if (this.specifier == null) {
            this.specifier = new ArrayList();
        }

        return this.specifier;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = ""
    )
    public static class Specifier {
        @XmlAttribute(
                name = "name",
                required = true
        )
        protected String name;
        @XmlAttribute(
                name = "type",
                required = true
        )
        protected String type;
        @XmlAttribute(
                name = "description"
        )
        protected String description;

        public Specifier() {
        }

        public String getName() {
            return this.name;
        }

        public void setName(String value) {
            this.name = value;
        }

        public String getType() {
            return this.type;
        }

        public void setType(String value) {
            this.type = value;
        }

        public String getDescription() {
            return this.description;
        }

        public void setDescription(String value) {
            this.description = value;
        }
    }
}

