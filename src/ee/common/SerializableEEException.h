/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

#ifndef SERIALIZABLEEEEXCEPTION_H_
#define SERIALIZABLEEEEXCEPTION_H_

#include <string>

#define throwSerializableEEException(...) { char msg[8192]; snprintf(msg, 8192, __VA_ARGS__); throw voltdb::UnexpectedEEException(msg); }

#define throwUnexpectedEEExceptionStreamed(STREAMABLES) {                          \
        std::ostringstream tUEEESbuffer;                                           \
        tUEEESbuffer << STREAMABLES << " " << __FILE__ << ":" << __LINE__ << "\n"; \
        throw voltdb::UnexpectedEEException(tUEEESbuffer.str()); }

// shorthand for ExecutionEngine versions generated by javah
#define ENGINE_ERRORCODE_SUCCESS 0
#define ENGINE_ERRORCODE_ERROR 1

namespace voltdb {

class ReferenceSerializeOutput;

enum VoltEEExceptionType {
    VOLT_EE_EXCEPTION_TYPE_NONE = 0,
    VOLT_EE_EXCEPTION_TYPE_EEEXCEPTION = 1,
    VOLT_EE_EXCEPTION_TYPE_SQL = 2,
    VOLT_EE_EXCEPTION_TYPE_CONSTRAINT_VIOLATION = 3,
    VOLT_EE_EXCEPTION_TYPE_INTERRUPT = 4,
};

/*
 * An "Exception" that can be generated by the EE, serialized into the
 * exception buffer of the Engine, and deserialized on the Java side
 * and then thrown as a Java exception. This can end up being a
 * SQLException or an EEException depending on the exception type.
 *
 * The VoltEEExceptionType enum is synonymous with the
 * SerializableException.SerializableExceptions enum in Java and is
 * used to determine what exception class will be used to deserialize
 * this exception.
 */
class SerializableEEException {
public:
    virtual ~SerializableEEException();

    void serialize(ReferenceSerializeOutput& output) const;
    virtual std::string message() const { return m_message; }
    VoltEEExceptionType getType() const { return m_exceptionType; }
    void appendContextToMessage(const std::string& more) { m_message += more; }
protected:
    SerializableEEException(VoltEEExceptionType exceptionType, std::string message);
    virtual void p_serialize(ReferenceSerializeOutput& output) const = 0;
private:
    const VoltEEExceptionType m_exceptionType;
    std::string m_message;

};

class UnexpectedEEException : public SerializableEEException {
public:
    UnexpectedEEException(std::string message)
      : SerializableEEException(VOLT_EE_EXCEPTION_TYPE_EEEXCEPTION, message)
    { }
protected:
    virtual void p_serialize(ReferenceSerializeOutput& output) const;
};

} // end namespace voltdb
#endif /* SERIALIZABLEEEEXCEPTION_H_ */
