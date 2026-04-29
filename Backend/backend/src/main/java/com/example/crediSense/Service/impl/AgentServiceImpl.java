package com.example.crediSense.Service.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.crediSense.Service.AgentService;
import com.example.crediSense.dto.request.AgentRequest;
import com.example.crediSense.dto.response.AgentResponse;
import com.example.crediSense.entity.Agent;
import com.example.crediSense.repository.AgentRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AgentServiceImpl  implements AgentService {

        private final AgentRepository agentRepository;

    @Override
    public AgentResponse create(AgentRequest request) {
        Agent agent = new Agent();
        agent.setNom(request.getNom());
        agent.setEmail(request.getEmail());
        agent.setRole(request.getRole());
        return toResponse(agentRepository.save(agent));
    }

    @Override
    public AgentResponse getById(UUID id) {
        return toResponse(agentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Agent non trouvé : " + id)));
    }

    @Override
    public List<AgentResponse> getAll() {
        return agentRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public AgentResponse update(UUID id, AgentRequest request) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Agent non trouvé : " + id));
        agent.setNom(request.getNom());
        agent.setEmail(request.getEmail());
        agent.setRole(request.getRole());
        return toResponse(agentRepository.save(agent));
    }

    @Override
    public void delete(UUID id) {
        agentRepository.deleteById(id);
    }

    private AgentResponse toResponse(Agent agent) {
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setNom(agent.getNom());
        response.setEmail(agent.getEmail());
        response.setRole(agent.getRole());
        response.setCreatedAt(agent.getCreatedAt());
        return response;
    }
}
