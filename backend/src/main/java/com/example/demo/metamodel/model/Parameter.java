package com.example.demo.metamodel.model;

/**
 * Parametro de un metodo (nombre + tipo, sin restriccion de catalogo ya que el tipo
 * de retorno/parametros de metodos es texto libre en el esquema canonico).
 */
public record Parameter(String name, String type) {
}
