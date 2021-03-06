package org.n52.matlab.control.link;

/*
 * Copyright (c) 2013, Joshua Kaplan
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *  - Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 *  - Neither the name of matlabcontrol nor the names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.lang.reflect.Array;
import java.util.Arrays;

import org.n52.matlab.control.MatlabInvocationException;
import org.n52.matlab.control.MatlabOperations;
import org.n52.matlab.control.link.ArrayMultidimensionalizer.PrimitiveArrayGetter;

/**
 * Acts as a MATLAB numeric array of any dimension. This representation is a copy of the MATLAB data, not a live view.
 * Retrieving large arrays from MATLAB can result in a {@link OutOfMemoryError}; if this occurs you may want to either
 * retrieve only part of the array from MATLAB or increase your Java Virtual Machine's heap size.
 * <br><br>
 * MATLAB numeric arrays of {@code int8}, {@code int16}, {@code int32}, {@code int64}, {@code single}, and
 * {@code double} are supported by subclasses. They will become {@code byte}, {@code short}, {@code int}, {@code long},
 * {@code float}, and {@code double} Java arrays respectively. MATLAB unsigned integer values ({@code uint8},
 * {@code uint16}, {@code uint32}, and {@code uint64}) are not supported.
 * <br><br>
 * Arrays in MATLAB are stored in a linear manner. The number and lengths of the dimensions are stored separately from
 * the real and imaginary value entries. Each dimension has a fixed length. (MATLAB's array implementation is known as
 * a dope vector.)
 * <br><br>
 * Java has no multidimensional array type. To support multiple dimensions, Java allows for creating arrays of any data
 * type, including arrays. (Java's array implementation is known as an Iliffe vector.) A two dimensional array of
 * {@code double}s, {@code double[][]}, is just an array of {@code double[]}. A result of this is that each
 * {@code double[]} can have a different length. When not all inner arrays for a given dimension have the same length,
 * then the array is known as as a jagged array (also known as a ragged array).
 * <br><br>
 * MATLAB arrays are always two or more dimensions; single dimension Java arrays will become MATLAB arrays of length 1
 * by <i>n</i> where <i>n</i> is the length of the Java array.
 * <br><br>
 * When an array is retrieved from MATLAB the resulting Java array is never jagged. When a {@code MatlabNumberArray} is
 * constructed from Java arrays, the arrays provided may be jagged. The bounding "box" of the provided array or arrays
 * is used and 0 is placed in all MATLAB array locations which do not have a corresponding Java array location.
 * <br><br>
 * While this class mimics the dimension and lengths of a MATLAB array, it uses Java's zero-based indexing convention
 * instead of MATLAB's one-based convention. For instance in MATLAB if an array were indexed into as
 * {@code array(3,4,7,2)}, then in Java to retrieve the same entry the indexing would be performed as
 * {@code array[2][3][6][1]}.
 * <br><br>
 * Once constructed, this class is unconditionally thread-safe. If the data provided to a constructor is modified while
 * construction is occurring, problems may occur.
 * 
 * @since 4.2.0
 * @author <a href="mailto:nonother@gmail.com">Joshua Kaplan</a>
 * 
 * @param <L> underlying linear array - single dimensional array type, ex. {@code byte[]}
 * @param <T> output array - primitive numeric array type, ex. {@code byte[][][]}
 *            (1 or more dimensions is acceptable, including for example {@code byte[]})
 */
abstract class MatlabNumberArray<L, T> extends MatlabType
{
    /**
     * The linear array of real values. Only intended to be accessed directly by subclasses inside this package.
     */
    final L _real;
    
    /**
     * The linear array of imaginary values. Can be {@code null} if this number array is real. Only intended to be
     * accessed directly by subclasses inside this package.
     */
    final L _imag;
    
    /**
     * The length of the linear arrays. Of course, not applicable to the imaginary array if it is {@code null}.
     */
    private final int _linearLength;
    
    /**
     * The lengths of each dimension of the array when represented as an array of type {@code T}.
     */
    private final int[] _dimensions;
    
    /**
     * The primitive numeric type stored by the arrays.
     */
    private final Class<?> _baseComponentType;
    
    /**
     * Internal linear array type.
     */
    private final Class<L> _linearArrayType;
    
    /**
     * Output array type.
     */
    private final Class<T> _outputArrayType;
    
    /**
     * Caches the hash code.
     * <br><br>
     * To avoid any form of inter-thread communication this value may in the most degenerate case be recomputed for each
     * thread.
     */
    private Integer _hashCode = null;
    
    /**
     * Caches if {@link #_imag} contains non-zero elements.
     * <br><br>
     * To avoid any form of inter-thread communication this value may in the most degenerate case be recomputed for each
     * thread.
     */
    private Boolean _hasImaginaryValues = null;
    
    /**
     * Data from MATLAB. Provided as the linear arrays and dimensions.
     * 
     * @param linearArrayType
     * @param realLinear
     * @param imagLinear
     * @param dimensions 
     */
    MatlabNumberArray(Class<L> linearArrayType, L real, L imag, int[] dimensions)
    {
        //The real and imaginary arrays should always be of type L, but validate it
        _real = linearArrayType.cast(real);
        _imag = linearArrayType.cast(imag);
        
        //Linear and multidimensional dimensions
        _linearLength = Array.getLength(real);
        _dimensions = dimensions;
        
        //Make class information at run time
        _baseComponentType = linearArrayType.getComponentType();
        _linearArrayType = linearArrayType;
        _outputArrayType = (Class<T>) ArrayUtils.getArrayClass(_baseComponentType, dimensions.length);
    }
    
    /**
     * Constructs a numeric array from Java arrays that can be transferred to MATLAB. The {@code imag} array
     * may be {@code null}, if so then this array will be real. References to the arrays passed in are not kept, and
     * modifying the array data after this class has been constructed will have no effect. If the data is modified
     * concurrently with this class's construction, problems may arise.
     * 
     * @param linearArrayType the type of the linear array(s), ex. {@code byte[]}
     * @param real may not be {@code null}
     * @param imag may be {@code null}
     * @throws NullPointerException if real array is null
     * @throws IllegalArgumentException if the arguments are not arrays with the component type of
     * {@code linearArrayType} or the two arrays are of different types
     */
    MatlabNumberArray(Class<L> linearArrayType, T real, T imag)
    {   
        //Real array cannot be null
        if(real == null)
        {
            throw new NullPointerException("Real array may not be null");
        }
        
        //Make class information available at run time
        _baseComponentType = linearArrayType.getComponentType();
        _linearArrayType = linearArrayType;
        _outputArrayType = (Class<T>) real.getClass();
        
        //Confirm real array is actually an array
        Class<?> realClass = real.getClass();
        if(!realClass.isArray())
        {
            throw new IllegalArgumentException("Real array is not an array, type: " + realClass.getCanonicalName());
        }
        
        //Confirm the real array is of the supported type
        Class<?> realBaseComponentType = ArrayUtils.getBaseComponentType(realClass);
        if(!realBaseComponentType.equals(_baseComponentType))
        {
            throw new IllegalArgumentException("Real array is not an array of the required class\n" +
                    "Required base component type: " + _baseComponentType.getCanonicalName() + "\n" +
                    "Provided base component type: " + realBaseComponentType.getCanonicalName());
        }
        
        //Confirm the imag array is of the same type as the real array
        if(imag != null && !imag.getClass().equals(realClass))
        {
            throw new IllegalArgumentException("Imaginary array is not of the same class as the real array\n" +
                    "Real array class: " + realClass.getCanonicalName() + "\n" +
                    "Imaginary array class: " + imag.getClass().getCanonicalName());
        }
        
        //Determine dimensions
        _dimensions = new int[ArrayUtils.getNumberOfDimensions(_outputArrayType)];
        int[] realDimensions = ArrayUtils.computeBoundingDimensions(real);
        for(int i = 0; i < realDimensions.length; i++)
        {
            _dimensions[i] = Math.max(_dimensions[i], realDimensions[i]);
        }
        if(imag != null)
        {
            int[] imagDimensions = ArrayUtils.computeBoundingDimensions(imag);
            for(int i = 0; i < imagDimensions.length; i++)
            {
                _dimensions[i] = Math.max(_dimensions[i], imagDimensions[i]);
            }
        }
        
        //Linearize arrays
        _real = _linearArrayType.cast(ArrayLinearizer.linearize(real, _dimensions));
        if(imag != null)
        {
            _imag = _linearArrayType.cast(ArrayLinearizer.linearize(imag, _dimensions));
        }
        else
        {   
            _imag = null;
            _hasImaginaryValues = false;
        }
        
        //Cache number of elements
        _linearLength = Array.getLength(_real);
    }
    
    /**
     * Returns {@code true} if the array has no imaginary values, {@code false} otherwise. Equivalent to the MATLAB
     * {@code isreal} function.
     * 
     * @return 
     */
    public boolean isReal()
    {
        if(_hasImaginaryValues == null)
        {   
            _hasImaginaryValues = containsNonZero(_imag);
        }
        
        return !_hasImaginaryValues;
    }
    
    abstract boolean containsNonZero(L array);
    
    /**
     * Returns an array that holds the real values from the MATLAB array. Each call returns a new copy which may be used
     * in any manner; modifications to it will have no effect on this instance.
     * 
     * @return real array
     */
    public T toRealArray()
    {
        return _outputArrayType.cast(ArrayMultidimensionalizer.multidimensionalize(_real, _dimensions));
    }
    
    /**
     * Returns an array that holds the imaginary values from the MATLAB array. Each call returns a new copy which may be
     * used in any manner; modifications to it will have no effect on this instance. If this array is real then the
     * returned array will be have {@code 0} as all of its base elements.
     * 
     * @return imaginary array
     */
    public T toImaginaryArray()
    {
        T array;
        if(isReal())
        {
            array = _outputArrayType.cast(Array.newInstance(_baseComponentType, _dimensions));
        }
        else
        {
            array = _outputArrayType.cast(ArrayMultidimensionalizer.multidimensionalize(_imag, _dimensions));
        }
        
        return array;
    }
    
    /**
     * The number of elements in the array. The real and imaginary components of a number are together considered one
     * element. This is equivalent to MATLAB's {@code numel} function.
     * 
     * @return number of elements
     */
    public int getNumberOfElements()
    {
        return _linearLength;
    }
    
    /**
     * Returns the length of the dimension specified by {@code dimension}. Dimensions use 0-based indexing. So the
     * first dimension, which is dimension 0, is the row length. The second dimension is the column length. The third
     * dimension and beyond are pages.
     * 
     * @param dimension
     * @return length of {@code dimension}
     * @throws IllegalArgumentException if {@code dimension} is not a dimension of the array
     */
    public int getLengthOfDimension(int dimension)
    {
        if(dimension >= _dimensions.length || dimension < 0)
        {
            throw new IllegalArgumentException(dimension + " is not a dimension of this array. This array has " +
                    getNumberOfDimensions() + " dimensions");
        }
        
        return _dimensions[dimension];
    }
    
    /**
     * Returns the number of dimensions of the array.
     * 
     * @return number of dimensions
     */
    public int getNumberOfDimensions()
    {
        return _dimensions.length;
    }
    
    /**
     * Gets the element at {@code index} treating this array as a MATLAB column vector. This is equivalent to indexing
     * into a MATLAB array with just one subscript.
     * 
     * @param index
     * @return element at {@code index}
     * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
     */
    public abstract MatlabNumber<?> getElementAtLinearIndex(int index);
    
    /**
     * Gets the element at the specified {@code row}, {@code column}, and {@code pages}.
     * 
     * @param row
     * @param column
     * @param pages
     * @return element at {@code row}, {@code column}, and {@code pages}
     * @throws IllegalArgumentException if number of indices does not equal this array's number of dimensions
     * @throws ArrayIndexOutOfBoundsException if the indices are out of bound
     */
    public abstract MatlabNumber<?> getElementAtIndices(int row, int column, int... pages);
    
    int getLinearIndex(int row, int column, int[] pages)
    {
        //Combine indices into one array
        int[] indices = new int[pages.length + 2];
        indices[0] = row;
        indices[1] = column;
        System.arraycopy(pages, 0, indices, 2, pages.length);
        
        if(indices.length == this.getNumberOfDimensions())
        {
            //Check the indices are in bounds
            for(int i = 0; i < indices.length; i++)
            {
                if(indices[i] >= _dimensions[i])
                {
                    throw new ArrayIndexOutOfBoundsException("[" + indices[i] + "] is out of bounds for dimension " +
                            i + " where the length is " + _dimensions[i]);
                }
            }
        }
        else
        {   
            throw new IllegalArgumentException("Array has " + this.getNumberOfDimensions() + " dimension(s), it " +
                    "cannot be indexed into using " + indices.length + " indices");
        }
        
        return ArrayUtils.multidimensionalIndicesToLinearIndex(_dimensions, indices);
    }
    
    /**
     * Returns a brief description of this array. The exact details of this representation are unspecified and are
     * subject to change.
     * 
     * @return 
     */
    @Override
    public String toString()
    {
        return "[" + this.getClass().getName() +
                " type=" + _outputArrayType.getCanonicalName() + "," +
                " real=" + isReal() + ", " +
                " numberOfElements=" + this.getNumberOfElements() + "," +
                " numberOfDimensions=" + this.getNumberOfDimensions() + "," +
                " lengthsOfDimensions=" + Arrays.toString(_dimensions) + "]";
    }

    @Override
    public boolean equals(Object obj)
    {   
        boolean equal = false;
        
        //Same object
        if(this == obj)
        {
            equal = true;
        }
        //Same class
        else if(obj != null && this.getClass().equals(obj.getClass()))
        {
            MatlabNumberArray other = (MatlabNumberArray) obj;
            
            //If the two instances are equal their hashcodes must be equal (but not the converse)
            if(this.hashCode() == other.hashCode())
            {
                //Same dimensions (number and length)
                if(Arrays.equals(_dimensions, other._dimensions))
                {
                    //Both real values, or both complex values
                    if((this.isReal() && other.isReal()) || (!this.isReal() && !other.isReal()))
                    {   
                        //Finally, compare the arrays
                        equal = this.equalsRealArray((L) other._real) && this.equalsImaginaryArray((L) other._imag);
                    }
                }
            }
        }
        
        return equal;
    }
    
    abstract boolean equalsRealArray(L other);
    
    abstract boolean equalsImaginaryArray(L other);

    @Override
    public int hashCode()
    {
        if(_hashCode == null)
        {
            int hashCode = 7;
            hashCode = 97 * hashCode + this.hashReal();
            hashCode = 97 * hashCode + this.hashImaginary();
            hashCode = 97 * hashCode + Arrays.hashCode(_dimensions);

            _hashCode = hashCode;
        }
        
        return _hashCode;
    }
    
    abstract int hashReal();
    
    abstract int hashImaginary();
    
    @Override
    MatlabTypeSetter getSetter()
    {
        return new MatlabNumberArraySetter(_real, _imag, _dimensions);
    }

    Class<T> getOutputArrayType()
    {
        return _outputArrayType;
    }
    
    private static class MatlabNumberArraySetter implements MatlabTypeSetter
    {
        private final Object _real;
        private final Object _imag;
        private final int[] _lengths;
        
        public MatlabNumberArraySetter(Object real, Object imag, int[] lengths)
        {
            _real = real;
            _imag = imag;
            _lengths = lengths;
        }
        
        @Override
        public void setInMatlab(MatlabOperations ops, String variableName) throws MatlabInvocationException
        {
            ops.setVariable(variableName, this);
            
            String command = variableName + " = reshape(" + variableName + ".getReal()";
            if(_imag != null)
            {
                command += " + " + variableName + ".getImaginary() * i";
            }
            
            if(_lengths.length == 1)
            {   
                command += ", 1, " + _lengths[0] + ");";
            }
            else
            {
                for(int length : _lengths)
                {
                    command += ", " + length;
                }
                command += ");";
            }

            ops.eval(command);
        }
        
        public Object getReal()
        {
            return _real;
        }
        
        public Object getImaginary()
        {
            return _imag;
        }
    }
    
    static class MatlabNumberArrayGetter implements MatlabTypeGetter
    {
        private Object _real;
        private Object _imag;
        private int[] _lengths;
        private boolean _retreived = false;
        
        private final boolean _keepLinear;
        
        MatlabNumberArrayGetter(boolean keepLinear)
        {
            _keepLinear = keepLinear;
        }
        
        @Override
        public MatlabNumberArray retrieve()
        {
            if(!_retreived)
            {
                throw new IllegalStateException("array has not been retrieved");
            }
            
            //Create the appropriate subclass
            MatlabNumberArray array;
            if(_real.getClass().equals(byte[].class))
            {
                array = new MatlabInt8Array((byte[]) _real, (byte[]) _imag, _lengths);
            }
            else if(_real.getClass().equals(short[].class))
            {
                array = new MatlabInt16Array((short[]) _real, (short[]) _imag, _lengths);
            }
            else if(_real.getClass().equals(int[].class))
            {
                array = new MatlabInt32Array((int[]) _real, (int[]) _imag, _lengths);
            }
            else if(_real.getClass().equals(long[].class))
            {
                array = new MatlabInt64Array((long[]) _real, (long[]) _imag, _lengths);
            }
            else if(_real.getClass().equals(float[].class))
            {
                array = new MatlabSingleArray((float[]) _real, (float[]) _imag, _lengths);
            }
            else if(_real.getClass().equals(double[].class))
            {
                array = new MatlabDoubleArray((double[]) _real, (double[]) _imag, _lengths);
            }
            else
            {
                throw new IllegalStateException("unsupported array type: " + _real.getClass().getCanonicalName());
            }
            
            return array;
        }

        @Override
        public void getInMatlab(MatlabOperations ops, String variableName) throws MatlabInvocationException
        {
            PrimitiveArrayGetter realGetter = new PrimitiveArrayGetter(true, true);
            realGetter.getInMatlab(ops, variableName);
            _real = realGetter.retrieve();
            
            if(_keepLinear)
            {
                _lengths = new int[] { realGetter.getLengths()[1] };
            }
            else
            {
                _lengths = realGetter.getLengths();
            }
            
            PrimitiveArrayGetter imagGetter = new PrimitiveArrayGetter(false, true);
            imagGetter.getInMatlab(ops, variableName);
            _imag = imagGetter.retrieve();
            
            _retreived = true;
        }
    }
}