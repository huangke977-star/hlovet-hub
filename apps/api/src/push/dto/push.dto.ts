import { Type } from "class-transformer";
import { IsIn, IsOptional, IsString, MaxLength, ValidateNested } from "class-validator";

export class PushSubscriptionKeysDto {
  @IsString()
  @MaxLength(255)
  p256dh!: string;

  @IsString()
  @MaxLength(255)
  auth!: string;
}

export class PushSubscriptionDto {
  @IsString()
  @MaxLength(4096)
  endpoint!: string;

  @ValidateNested()
  @Type(() => PushSubscriptionKeysDto)
  keys!: PushSubscriptionKeysDto;
}

export class NativePushTokenDto {
  @IsString()
  @MaxLength(4096)
  token!: string;

  @IsOptional()
  @IsIn(["android"])
  platform?: "android";

  @IsOptional()
  @IsString()
  @MaxLength(191)
  appId?: string;

  @IsOptional()
  @IsString()
  @MaxLength(191)
  deviceId?: string;
}
