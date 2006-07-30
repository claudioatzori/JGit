/*
 *    Copyright 2006 Shawn Pearce <spearce@spearce.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spearce.jgit.lib;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.spearce.jgit.errors.ObjectWritingException;

public class ObjectWriter
{
    private final Repository r;

    private final byte[] buf;

    private final MessageDigest md;

    private final Deflater def;

    private final boolean legacyHeaders;

    public ObjectWriter(final Repository d)
    {
        r = d;
        buf = new byte[8192];
        md = Constants.newMessageDigest();
        def = new Deflater(r.getConfig().getCore().getCompression());
        legacyHeaders = r.getConfig().getCore().useLegacyHeaders();
    }

    public ObjectId writeBlob(final byte[] b) throws IOException
    {
        return writeBlob(b.length, new ByteArrayInputStream(b));
    }

    public ObjectId writeBlob(final File f) throws IOException
    {
        final FileInputStream is = new FileInputStream(f);
        try
        {
            return writeBlob(f.length(), is);
        }
        finally
        {
            is.close();
        }
    }

    public ObjectId writeBlob(final long len, final InputStream is)
        throws IOException
    {
        return writeObject(Constants.OBJ_BLOB, Constants.TYPE_BLOB, len, is);
    }

    public ObjectId writeTree(final Tree t) throws IOException
    {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final TreeEntry[] items = t.members();
        byte[] last = null;
        for (int k = 0; k < items.length; k++)
        {
            final TreeEntry e = items[k];
            final byte[] name = e.getNameUTF8();
            final ObjectId id = e.getId();

            if (id == null)
            {
                throw new ObjectWritingException("Object at path \""
                    + e.getFullName()
                    + "\" does not have an id assigned."
                    + "  All object ids must be assigned prior"
                    + " to writing a tree.");
            }

            // Make damn sure the tree object is formatted properly as writing
            // an incorrectly sorted tree would create a corrupt object that
            // nobody could later read.
            // 
            if (last != null && Tree.compareNames(last, name) >= 0)
            {
                throw new ObjectWritingException("Tree \""
                    + t.getFullName()
                    + "\" is not sorted according to object names.");
            }

            e.getMode().copyTo(o);
            o.write(' ');
            o.write(name);
            o.write(0);
            o.write(id.getBytes());
            last = name;
        }
        return writeTree(o.toByteArray());
    }

    public ObjectId writeTree(final byte[] b) throws IOException
    {
        return writeTree(b.length, new ByteArrayInputStream(b));
    }

    public ObjectId writeTree(final long len, final InputStream is)
        throws IOException
    {
        return writeObject(Constants.OBJ_TREE, Constants.TYPE_TREE, len, is);
    }

    public ObjectId writeCommit(final Commit c) throws IOException
    {
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final OutputStreamWriter w = new OutputStreamWriter(
            os,
            Constants.CHARACTER_ENCODING);

        w.write("tree ");
        c.getTreeId().copyTo(w);
        w.write('\n');

        final Iterator i = c.getParentIds().iterator();
        while (i.hasNext())
        {
            w.write("parent ");
            ((ObjectId) i.next()).copyTo(w);
            w.write('\n');
        }

        w.write("author ");
        w.write(c.getAuthor().toExternalString());
        w.write('\n');

        w.write("committer ");
        w.write(c.getCommitter().toExternalString());
        w.write('\n');

        w.write('\n');
        w.write(c.getMessage());
        w.close();

        return writeCommit(os.toByteArray());
    }

    public ObjectId writeCommit(final byte[] b) throws IOException
    {
        return writeCommit(b.length, new ByteArrayInputStream(b));
    }

    public ObjectId writeCommit(final long len, final InputStream is)
        throws IOException
    {
        return writeObject(Constants.OBJ_COMMIT, Constants.TYPE_COMMIT, len, is);
    }

    public ObjectId writeObject(
        final int typeCode,
        final String type,
        long len,
        final InputStream is) throws IOException
    {
        final File t;
        final DeflaterOutputStream deflateStream;
        final FileOutputStream fileStream;
        ObjectId id = null;

        t = File.createTempFile("noz", null, r.getObjectsDirectory());
        fileStream = new FileOutputStream(t);
        if (!legacyHeaders)
        {
            long sz = len;
            int c = ((typeCode & 7) << 4) | (int) (sz & 0xf);
            sz >>= 4;
            while (sz > 0)
            {
                fileStream.write(c | 0x80);
                c = (int) (sz & 0x7f);
                sz >>= 7;
            }
            fileStream.write(c);
        }

        md.reset();
        def.reset();
        deflateStream = new DeflaterOutputStream(fileStream, def);

        try
        {
            byte[] header;
            int r;

            header = Constants.encodeASCII(type);
            md.update(header);
            if (legacyHeaders)
                deflateStream.write(header);

            md.update((byte) ' ');
            if (legacyHeaders)
                deflateStream.write((byte) ' ');

            header = Constants.encodeASCII(len);
            md.update(header);
            if (legacyHeaders)
                deflateStream.write(header);

            md.update((byte) 0);
            if (legacyHeaders)
                deflateStream.write((byte) 0);

            while (len > 0
                && (r = is.read(buf, 0, (int) Math.min(len, buf.length))) > 0)
            {
                md.update(buf, 0, r);
                deflateStream.write(buf, 0, r);
                len -= r;
            }

            if (len != 0)
            {
                throw new IOException("Input did not match supplied length. "
                    + len
                    + " bytes are missing.");
            }

            deflateStream.close();
            t.setReadOnly();
            id = new ObjectId(md.digest());
        }
        finally
        {
            if (id == null)
            {
                try
                {
                    deflateStream.close();
                }
                finally
                {
                    t.delete();
                }
            }
        }

        if (r.hasObject(id))
        {
            // Object is already in the repository so remove the temporary file.
            //
            t.delete();
        }
        else
        {
            final File o = r.toFile(id);
            if (!t.renameTo(o))
            {
                // Maybe the directory doesn't exist yet as the object
                // directories are always lazilly created. Note that we
                // try the rename first as the directory likely does exist.
                //
                o.getParentFile().mkdir();
                if (!t.renameTo(o))
                {
                    if (!r.hasObject(id))
                    {
                        // The object failed to be renamed into its proper
                        // location and it doesn't exist in the repository
                        // either. We really don't know what went wrong, so
                        // fail.
                        //
                        t.delete();
                        throw new ObjectWritingException("Unable to"
                            + " create new object: "
                            + o);
                    }
                }
            }
        }

        return id;
    }
}
