/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.auth;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.server.security.auth.exception.ConcurrentModificationException;
import org.neo4j.server.security.auth.exception.IllegalTokenException;
import org.neo4j.server.security.auth.exception.IllegalUsernameException;

/**
 * Stores user auth data. In memory, but backed by persistent storage so changes to this repository will survive
 * JVM restarts and crashes.
 */
public class FileUserRepository extends LifecycleAdapter implements UserRepository
{
    private final FileSystemAbstraction fs;
    private final File dbFile;

    /**
     * Used while writing to the dbfile, the whole file is first written to this file, so that we can recover
     * if we crash.
     */
    private final File tempFile;

    /** Quick lookup of users by name */
    private final Map<String, User> usersByName = new ConcurrentHashMap<>();

    /** Quick lookup of users by token */
    private final Map<String, User> usersByToken = new ConcurrentHashMap<>();

    /** Master list of users */
    private volatile List<User> users = new ArrayList<>();

    private final UserSerialization serialization = new UserSerialization();

    public FileUserRepository( FileSystemAbstraction fs, File file )
    {
        this.fs = fs;
        this.dbFile = file;
        this.tempFile = new File(file.getAbsolutePath() + ".tmp");
    }

    @Override
    public User findByName( String name )
    {
        return usersByName.get( name );
    }

    @Override
    public User findByToken( String name )
    {
        return usersByToken.get( name );
    }

    @Override
    public void start() throws Throwable
    {
        if(fs.fileExists( dbFile ))
        {
            loadUsersFromFile(dbFile);
        }
        else if(fs.fileExists( tempFile ))
        {
            fs.renameFile( tempFile, dbFile );
            loadUsersFromFile( dbFile );
            fs.deleteFile( tempFile );
        }
    }

    @Override
    public void create( User user ) throws IllegalUsernameException, IllegalTokenException, IOException
    {
        if ( !isValidName( user.name() ) )
        {
            throw new IllegalUsernameException( "'" + user.name() + "' is not a valid user name." );
        }
        if ( !isValidToken( user.token() ) )
        {
            throw new IllegalTokenException( "Invalid token" );
        }

        synchronized (this)
        {
            // Check for existing user or token
            for ( User other : users )
            {
                if ( other.name().equals( user.name() ) )
                {
                    throw new IllegalUsernameException( "The specified user already exists" );
                }
                if ( other.token().equals( user.token() ) )
                {
                    throw new IllegalTokenException( "The specified token is already in use" );
                }
            }

            users.add( user );

            commitToDisk();

            usersByName.put( user.name(), user );
            usersByToken.put( user.token(), user );
        }
    }

    @Override
    public void update( User existingUser, User updatedUser ) throws IllegalTokenException, ConcurrentModificationException, IOException
    {
        // Assert input is ok
        if ( !existingUser.name().equals( updatedUser.name() ) )
        {
            throw new IllegalArgumentException( "updatedUser has a different name" );
        }
        if ( !isValidToken( updatedUser.token() ) )
        {
            throw new IllegalTokenException( "Invalid token" );
        }

        synchronized (this)
        {
            // Copy-on-write for the users list
            List<User> newUsers = new ArrayList<>();
            boolean foundUser = false;
            for ( User other : users )
            {
                if ( other.equals( existingUser ) )
                {
                    foundUser = true;
                    newUsers.add( updatedUser );
                } else if ( other.token().equals( updatedUser.token() ) )
                {
                    throw new IllegalTokenException( "The specified token is already in use" );
                } else
                {
                    newUsers.add( other );
                }
            }

            if ( !foundUser )
            {
                throw new ConcurrentModificationException();
            }

            users = newUsers;

            commitToDisk();

            usersByName.put( updatedUser.name(), updatedUser );
            usersByToken.remove( existingUser.token() );
            usersByToken.put( updatedUser.token(), updatedUser );
        }
    }

    @Override
    public int numberOfUsers()
    {
        return users.size();
    }

    @Override
    public boolean isValidName( String name )
    {
        return name.matches( "^[a-zA-Z0-9_]+$" );
    }

    @Override
    public boolean isValidToken( String token )
    {
        if (token == null)
        {
            throw new IllegalArgumentException( "token should not be null" );
        }
        return token.matches( "^[a-fA-F0-9]+$" );
    }

    /* Assumes synchronization elsewhere */
    private void commitToDisk() throws IOException
    {
        writeUsersToFile( tempFile );
        writeUsersToFile( dbFile );
        fs.deleteFile( tempFile );
    }

    private void writeUsersToFile( File fileToWriteTo ) throws IOException
    {
        if(!fs.fileExists( fileToWriteTo.getParentFile() ))
        {
            fs.mkdirs( fileToWriteTo.getParentFile() );
        }
        if(fs.fileExists( fileToWriteTo ))
        {
            fs.deleteFile( fileToWriteTo );
        }
        try(OutputStream out = fs.openAsOutputStream( fileToWriteTo, false ))
        {
            out.write( serialization.serialize( users ) );
            out.flush();
        }
    }

    private void loadUsersFromFile( File fileToLoadFrom ) throws IOException
    {
        if(fs.fileExists( fileToLoadFrom ))
        {
            List<User> loadedUsers;
            try(InputStream in = fs.openAsInputStream( fileToLoadFrom ))
            {
                byte[] bytes = new byte[(int)fs.getFileSize( fileToLoadFrom )];
                int offset = 0;
                while(offset < bytes.length)
                {
                    int read = in.read( bytes, offset, bytes.length - offset );
                    if(read == -1) break;
                    offset += read;
                }
                loadedUsers = serialization.deserializeUsers( bytes );
            }

            if(loadedUsers == null)
            {
                throw new IllegalStateException( "Failed to read authentication file: " + fileToLoadFrom.getAbsolutePath() );
            }

            users = loadedUsers;
            for ( User user : users )
            {
                usersByName.put( user.name(), user );
                usersByToken.put( user.token(), user );
            }
        }
    }
}
