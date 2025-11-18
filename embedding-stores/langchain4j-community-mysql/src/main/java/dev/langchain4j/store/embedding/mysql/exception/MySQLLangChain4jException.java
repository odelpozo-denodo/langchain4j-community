package dev.langchain4j.store.embedding.mysql.exception;

import dev.langchain4j.exception.LangChain4jException;

import java.io.Serial;

/**
 * Represents an exception specific to MySQL interactions within the LangChain4j framework.
 * This exception is typically thrown when there are errors or issues related to MySQL
 * operations in the context of LangChain4j.
 *
 * This is a subclass of {@code LangChain4jException}, which provides a way to handle
 * MySQL-specific exceptions while still leveraging the generic exception handling
 * capabilities of LangChain4j.
 *
 * Constructors in this class allow for specifying a cause, a message, or both
 * to provide more detailed context about the exception.
 */
public class MySQLLangChain4jException extends LangChain4jException {

    @Serial
    private static final long serialVersionUID = -3265265659500655484L;

    public MySQLLangChain4jException(final Throwable cause) {
        super(cause);
    }

    public MySQLLangChain4jException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
