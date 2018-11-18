package play.modules.elasticsearch.mapping;

import java.lang.reflect.Field;

/**
 * Factory for retrieving {@link ModelMapper}s and {@link FieldMapper}s
 */
public interface MapperFactory {

  /**
   * Gets a {@link ModelMapper} for the specified model class
   *
   * @param <M> the model type
   * @param clazz the model class
   * @return the model mapper
   * @throws MappingException in case of mapping problems
   */
  <M> ModelMapper<M> getMapper(Class<M> clazz) throws MappingException;

  /**
   * Gets a {@link FieldMapper} for the specified field
   *
   * @param <M> the model type
   * @param field the field
   * @return the field mapper
   * @throws MappingException in case of mapping problems
   */
  <M> FieldMapper<M> getMapper(Field field) throws MappingException;

  /**
   * Gets a {@link FieldMapper} for the specified field, using a prefix in the index
   *
   * @param <M> the model type
   * @param field the field
   * @return the field mapper
   * @throws MappingException in case of mapping problems
   */
  <M> FieldMapper<M> getMapper(Field field, String prefix) throws MappingException;
}
