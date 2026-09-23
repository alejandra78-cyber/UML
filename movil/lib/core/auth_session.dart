class AuthSession {
  const AuthSession({
    required this.token,
    required this.userId,
    required this.email,
    required this.fullName,
  });

  final String token;
  final String userId;
  final String email;
  final String fullName;

  factory AuthSession.fromJson(Map<String, dynamic> json) => AuthSession(
        token: json['token'] as String,
        userId: json['userId'] as String,
        email: json['email'] as String,
        fullName: json['fullName'] as String,
      );

  Map<String, dynamic> toJson() => {
        'token': token,
        'userId': userId,
        'email': email,
        'fullName': fullName,
      };
}
