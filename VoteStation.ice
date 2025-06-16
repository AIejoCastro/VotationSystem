//
// Interface ICE para VoteStation según especificación
// Facilita automatización de pruebas
//

#pragma once
module VotingStation
{
    /**
     * Interfaz principal para estaciones de votación
     * Retorna:
     * - 0: Voto exitoso
     * - 2: Ciudadano ya votó (voto duplicado)
     * - Otros códigos para diferentes errores
     */
    interface VoteStation
    {
        /**
         * Procesar voto de ciudadano
         * @param document - Documento de identidad del ciudadano
         * @param candidateId - ID numérico del candidato (1-5)
         * @return Código de resultado (0=éxito, 2=duplicado)
         */
        int vote(string document, int candidateId);

        /**
         * Obtener estado de la estación de votación
         * @return Estado actual del sistema
         */
        string getStationStatus();

        /**
         * Obtener lista de candidatos disponibles
         * @return Información de candidatos
         */
        string getCandidateList();

        /**
         * Verificar si un ciudadano ya votó
         * @param document - Documento de identidad
         * @return true si ya votó, false si no
         */
        bool hasVoted(string document);

        /**
         * Shutdown de la estación de votación
         */
        void shutdown();
    }
}