package com.safedrive.server.controller;

import com.safedrive.server.dto.LoginRequest;
import com.safedrive.server.model.Usuario;
import com.safedrive.server.repository.UsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UsuarioRepository usuarioRepository;

    public AuthController(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        Optional<Usuario> usuarioOpt = usuarioRepository.findByDni(request.getDni());

        if (usuarioOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Usuario no encontrado");
        }

        Usuario usuario = usuarioOpt.get();

        if (!usuario.getPasswordHash().equals(request.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Contraseña incorrecta");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", usuario.getId());
        response.put("dni", usuario.getDni());
        response.put("nombreCompleto", usuario.getNombreCompleto());
        response.put("rol", usuario.getRol());

        return ResponseEntity.ok(response);
    }
}