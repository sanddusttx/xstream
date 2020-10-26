/*
 * Copyright (C) 2003, 2004 Joe Walnes.
 * Copyright (C) 2006, 2007, 2018 XStream Committers.
 * All rights reserved.
 *
 * The software in this package is published under the terms of the BSD
 * style license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 *
 * Created on 26. September 2003 by Joe Walnes
 */
package com.thoughtworks.acceptance.objects;

public class OpenSourceSoftware extends Software {
    private static final long serialVersionUID = 200310L;
    String license;

    public OpenSourceSoftware() {
    }

    public OpenSourceSoftware(final String vendor, final String name, final String license) {
        super(vendor, name);
        this.license = license;
    }

}
