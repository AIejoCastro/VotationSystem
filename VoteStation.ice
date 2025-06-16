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
     * - 3: Ciudadano no registrado en base de datos
     * - Otros códigos para diferentes errores
     */
    interface VoteStation
    {

        int vote(string document, int candidateId);

        string getStationStatus();
        string getCandidateList();
        bool hasVoted(string document);
        void shutdown();
    }
}