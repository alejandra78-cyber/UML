package com.example.demo.metamodel.service;

import com.example.demo.metamodel.model.CanonicalModel;
import com.example.demo.metamodel.model.ClassEntity;
import com.example.demo.metamodel.model.Position;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Motor de auto-layout deterministico en grilla (RF-02.5), usado para posicionar
 * clases creadas por comandos de voz/texto sin solaparlas con clases existentes.
 *
 * <p>La grilla usa celdas de {@value #CELL_WIDTH}x{@value #CELL_HEIGHT} px, con
 * origen en (0,0). El algoritmo es deterministico: para la misma coleccion de
 * clases existentes y el mismo N de clases nuevas, siempre devuelve las mismas
 * posiciones, porque escanea las celdas en orden creciente de fila y luego
 * columna (sin depender del orden de iteracion de ningun {@link Set}/{@link Map}).</p>
 */
@Service
public class GridLayoutEngine {

    public static final double CELL_WIDTH = 340;
    public static final double CELL_HEIGHT = 260;

    /**
     * Calcula posiciones para {@code count} clases nuevas, evitando las celdas ya
     * ocupadas por las clases existentes en el modelo.
     */
    public List<Position> computePositions(CanonicalModel model, int count) {
        return computePositions(model.classes(), count);
    }

    /**
     * Calcula posiciones para {@code count} clases nuevas, evitando las celdas ya
     * ocupadas por {@code existingClasses}.
     */
    public List<Position> computePositions(Collection<ClassEntity> existingClasses, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count no puede ser negativo");
        }
        Set<GridCell> occupied = new HashSet<>();
        if (existingClasses != null) {
            for (ClassEntity clazz : existingClasses) {
                occupied.add(toGridCell(clazz.position()));
            }
        }

        List<Position> result = new ArrayList<>(count);
        int row = 0;
        int col = 0;
        while (result.size() < count) {
            GridCell candidate = new GridCell(col, row);
            if (!occupied.contains(candidate)) {
                occupied.add(candidate);
                result.add(toPixelPosition(candidate));
            }
            col++;
            if (col >= MAX_COLUMNS_PER_ROW) {
                col = 0;
                row++;
            }
        }
        return result;
    }

    /**
     * Numero de columnas antes de saltar a la siguiente fila. Un valor fijo
     * (no infinito) garantiza un recorrido de la grilla predecible y acotado.
     */
    private static final int MAX_COLUMNS_PER_ROW = 8;

    private GridCell toGridCell(Position position) {
        int col = (int) Math.round(position.x() / CELL_WIDTH);
        int row = (int) Math.round(position.y() / CELL_HEIGHT);
        return new GridCell(col, row);
    }

    private Position toPixelPosition(GridCell cell) {
        return new Position(cell.col() * CELL_WIDTH, cell.row() * CELL_HEIGHT);
    }

    private record GridCell(int col, int row) {
    }
}
